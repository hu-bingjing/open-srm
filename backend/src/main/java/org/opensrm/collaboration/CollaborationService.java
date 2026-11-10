package org.opensrm.collaboration;
import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import org.opensrm.access.AccessPolicy;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.*;
import org.opensrm.purchasing.OrderService;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CollaborationService {
 public record ConfirmInput(@NotNull UUID scheduleId,@NotBlank @Size(max=80) String sourceRevision,@NotNull @Pattern(regexp="(?:0|[1-9][0-9]{0,12})(?:[.][0-9]{1,6})?") String quantity,@NotNull LocalDate promisedDate,@Size(max=1000) String reason) { }
 public record ChangeInput(@NotNull UUID confirmationId,@NotBlank @Size(max=80) String sourceRevision,@NotNull LocalDate proposedDate,@NotBlank @Size(max=1000) String reason) { }
 public record Decision(@NotNull @Pattern(regexp="APPROVED|REJECTED") String decision,@NotBlank @Size(max=1000) String reason) { }
 public record Confirmation(UUID id,UUID orderId,UUID scheduleId,String externalOrder,String externalItem,String externalSchedule,String plantCode,String sourceRevision,String quantity,String promisedDate,String status,UUID requesterId,long version) { }
 public record Change(UUID id,UUID orderId,UUID confirmationId,UUID scheduleId,String externalOrder,String externalItem,String externalSchedule,String plantCode,String sourceRevision,String kind,String originalDate,String proposedDate,String reason,String status,UUID requesterId,long version) { }
 public record Plan(UUID id,UUID orderId,UUID confirmationId,UUID scheduleId,String externalOrder,String externalItem,String externalSchedule,String plantCode,String sourceRevision,String quantity,String dueDate,String status,long version) { }
 public record Approval(UUID id,UUID actorId,String decision,String reason,String createdAt) { }
 private record Ref(UUID order,UUID revision,UUID currentRevision,UUID schedule,UUID company,UUID supplier,UUID plant,UUID purchasingOrg,String sourceRevision,long orderVersion,String lifecycle,boolean hold,String supplierStatus,LocalDate requestedDate,BigDecimal ordered) { }
 private final JdbcTemplate db;private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;private final IdentityCommands commands;private final OrderService orders;private final CollaborationLifecycle events;
 public CollaborationService(JdbcTemplate db,AccessPolicy policy,IdentityCommands commands,OrderService orders,CollaborationLifecycle events) {this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.commands=commands;this.orders=orders;this.events=events;}
 private String scope(AccessPolicy.Context ctx) {return orders.visible(ctx)+" AND EXISTS(SELECT 1 FROM plant ap WHERE ap.id=i.plant_id AND ap.active) AND "+policy.grant("o.company_id","i.plant_id","o.purchasing_org_id",AccessPolicy.Permission.ORDER);}
 private static final String BASE="po_schedule s JOIN po_item i ON i.id=s.item_id JOIN po_revision r ON r.id=i.revision_id JOIN purchase_order o ON o.id=r.order_id JOIN plant p ON p.id=i.plant_id";
 private String tableJoin(String kind) {
  return switch(kind) {
   case "confirmation" -> "confirmation x JOIN po_schedule s ON s.id=x.schedule_id";
   case "delivery_change" -> "delivery_change x JOIN confirmation c ON c.id=x.confirmation_id JOIN po_schedule s ON s.id=c.schedule_id";
   case "delivery_plan" -> "delivery_plan x JOIN po_schedule s ON s.id=x.schedule_id";
   default -> throw new IllegalArgumentException();
  }+" JOIN po_item i ON i.id=s.item_id JOIN po_revision r ON r.id=i.revision_id JOIN purchase_order o ON o.id=r.order_id JOIN plant p ON p.id=i.plant_id";
 }
 private static final RowMapper<Confirmation> CONFIRM=(r,n)->new Confirmation(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("schedule_id",UUID.class),r.getString("external_id"),r.getString("external_item"),r.getString("external_schedule"),r.getString("plant_code"),r.getString("source_revision"),r.getBigDecimal("quantity").toPlainString(),r.getDate("promised_date").toString(),r.getString("status"),r.getObject("requester_id",UUID.class),r.getLong("lock_version"));
 private static final RowMapper<Change> CHANGE=(r,n)->new Change(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("confirmation_id",UUID.class),r.getObject("line_schedule_id",UUID.class),r.getString("external_id"),r.getString("external_item"),r.getString("external_schedule"),r.getString("plant_code"),r.getString("source_revision"),r.getString("kind"),r.getDate("original_date").toString(),r.getDate("proposed_date").toString(),r.getString("reason"),r.getString("status"),r.getObject("requester_id",UUID.class),r.getLong("lock_version"));
 private static final RowMapper<Plan> PLAN=(r,n)->new Plan(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("confirmation_id",UUID.class),r.getObject("schedule_id",UUID.class),r.getString("external_id"),r.getString("external_item"),r.getString("external_schedule"),r.getString("plant_code"),r.getString("source_revision"),r.getBigDecimal("quantity").toPlainString(),r.getDate("due_date").toString(),r.getString("status"),r.getLong("lock_version"));
 private String columns() {return "SELECT x.*,o.external_id,i.external_item,s.external_schedule,s.id line_schedule_id,p.code plant_code FROM ";}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Object list(SessionUser actor,String kind,int page,int size,UUID order,UUID company,UUID plant,String status) {
  var ctx=policy.context(actor,"order.read");var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);
  String where=scope(ctx)+" AND x.order_id=o.id";
  if(order!=null) {args.addValue("order",order);where+=" AND o.id=:order";}
  if(company!=null) {args.addValue("company",company);where+=" AND o.company_id=:company";}
  if(plant!=null) {args.addValue("plant",plant);where+=" AND i.plant_id=:plant";}
  if(kind.equals("delivery_plan") && status.equals("OPEN")) where+=" AND x.status IN ('PLANNED','PARTIALLY_SHIPPED')";else if(!status.isBlank()) {args.addValue("status",status);where+=" AND x.status=:status";}
  RowMapper<?> mapper=switch(kind) {case "confirmation"->CONFIRM;case "delivery_change"->CHANGE;case "delivery_plan"->PLAN;default->throw new IllegalArgumentException();};
  var rows=sql.query(columns()+tableJoin(kind)+" WHERE "+where+" ORDER BY x.created_at DESC,x.id LIMIT :limit OFFSET :offset",args,mapper);
  return new IdentityService.Page<>(rows,sql.queryForObject("SELECT count(*) FROM "+tableJoin(kind)+" WHERE "+where,args,Long.class),page,size);
 }
 private <T> T get(SessionUser actor,String kind,UUID id,RowMapper<T> mapper) {
  var ctx=policy.context(actor,"order.read");var rows=sql.query(columns()+tableJoin(kind)+" WHERE x.id=:id AND x.order_id=o.id AND "+scope(ctx),policy.parameters(ctx).addValue("id",id),mapper);
  if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
 }
 public Confirmation confirmation(SessionUser actor,UUID id) {return get(actor,"confirmation",id,CONFIRM);}
 public Change change(SessionUser actor,UUID id) {return get(actor,"delivery_change",id,CHANGE);}
 public Plan plan(SessionUser actor,UUID id) {return get(actor,"delivery_plan",id,PLAN);}
 public List<Approval> approvals(SessionUser actor,UUID id) {change(actor,id);return db.query("SELECT * FROM approval_event WHERE change_id=? ORDER BY created_at,id",(r,n)->new Approval(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("decision"),r.getString("reason"),r.getTimestamp("created_at").toInstant().toString()),id);}
 private Ref ref(SessionUser actor,UUID schedule) {
  var ctx=policy.context(actor,"order.read");var rows=sql.query("SELECT o.id order_id,o.current_revision_id,o.company_id,o.supplier_id,o.purchasing_org_id,o.lock_version,o.lifecycle,o.consistency_hold,r.id revision_id,r.source_revision,s.id schedule_id,s.ordered_quantity,s.requested_date,i.plant_id,sv.status supplier_status FROM "+BASE+" JOIN supplier sv ON sv.id=o.supplier_id WHERE s.id=:schedule AND "+scope(ctx),policy.parameters(ctx).addValue("schedule",schedule),(r,n)->new Ref(r.getObject("order_id",UUID.class),r.getObject("revision_id",UUID.class),r.getObject("current_revision_id",UUID.class),r.getObject("schedule_id",UUID.class),r.getObject("company_id",UUID.class),r.getObject("supplier_id",UUID.class),r.getObject("plant_id",UUID.class),r.getObject("purchasing_org_id",UUID.class),r.getString("source_revision"),r.getLong("lock_version"),r.getString("lifecycle"),r.getBoolean("consistency_hold"),r.getString("supplier_status"),r.getDate("requested_date").toLocalDate(),r.getBigDecimal("ordered_quantity")));
  if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
 }
 private void live(Ref r,String revision) {if(!r.revision().equals(r.currentRevision()) || !r.sourceRevision().equals(revision)) throw new ApiProblem(409,"SOURCE_REVISION_CONFLICT");if(!r.lifecycle().equals("ACTIVE")) throw new ApiProblem(409,"ORDER_NOT_ACTIVE");if(r.hold()) throw new ApiProblem(409,"CONSISTENCY_HOLD");if(!r.supplierStatus().equals("ACTIVE")) throw new ApiProblem(409,"SUPPLIER_FROZEN");}
 private void actionScope(SessionUser actor,Ref r,AccessPolicy.Permission permission,String capability) {
  var ctx=policy.context(actor,capability);var args=policy.parameters(ctx).addValue("company",r.company()).addValue("plant",r.plant()).addValue("porg",r.purchasingOrg());
  if(!Boolean.TRUE.equals(sql.queryForObject("SELECT "+policy.currentActor()+" AND "+policy.grant(":company",":plant",":porg",permission),args,Boolean.class))) throw new ApiProblem(404,"NOT_FOUND");
 }
 private Ref supplier(SessionUser actor,UUID order,UUID schedule,String revision) {var r=ref(actor,schedule);if(!r.order().equals(order)) throw new ApiProblem(404,"NOT_FOUND");var ctx=policy.context(actor,"confirmation.submit");if(!ctx.supplierUser() || !r.supplier().equals(ctx.supplierId())) throw new ApiProblem(403,"SUPPLIER_IDENTITY_REQUIRED");actionScope(actor,r,AccessPolicy.Permission.CONFIRM,"confirmation.submit");live(r,revision);return r;}
 private void bump(Ref r) {db.update("UPDATE purchase_order SET lock_version=lock_version+1 WHERE id=?",r.order());}
 private IdentityCommands.Outcome outcome(Object body,UUID id,long version,Ref r) {return new IdentityCommands.Outcome(body,id,version,"COLLABORATION",r.supplier(),List.of(r.company()));}
 private void emit(Ref r,UUID aggregate,long version,String type) {events.event(r.order(),aggregate,version,r.sourceRevision(),type);}
 private void plan(Ref r,UUID confirmation,long version,BigDecimal quantity,LocalDate date) {UUID id=UUID.randomUUID();db.update("INSERT INTO delivery_plan(id,deployment_id,order_id,confirmation_id,confirmation_version,schedule_id,source_revision,quantity,due_date,status) SELECT ?,deployment_id,?,?,?,?,?,?,?,'PLANNED' FROM app_guard WHERE id=1",id,r.order(),confirmation,version,r.schedule(),r.sourceRevision(),quantity,date);emit(r,id,0,"delivery-plan.planned");}
 private UUID insertChange(Ref r,UUID confirmation,String kind,LocalDate original,LocalDate proposed,String reason,UUID requester,long version) {UUID id=UUID.randomUUID();db.update("INSERT INTO delivery_change(id,deployment_id,order_id,confirmation_id,source_revision,kind,original_date,proposed_date,reason,requester_id,base_confirmation_version,status) SELECT ?,deployment_id,?,?,?,?,?,?,?,?,?,'SUBMITTED' FROM app_guard WHERE id=1",id,r.order(),confirmation,r.sourceRevision(),kind,original,proposed,reason.trim(),requester,version);emit(r,id,0,"delivery-change.submitted");return id;}
 public IdentityService.StoredResult submit(SessionUser actor,UUID order,String key,String etag,ConfirmInput input) {
  return commands.execute(actor,"confirmation.submit","confirmation.submit:"+order,key,List.of(etag,input),()->supplier(actor,order,input.scheduleId(),input.sourceRevision()),body->{},()->{
   Ref r=supplier(actor,order,input.scheduleId(),input.sourceRevision());IdentityCommands.match(r.orderVersion(),etag);BigDecimal q=CollaborationRules.quantity(input.quantity());
   BigDecimal reserved=db.queryForObject("SELECT COALESCE(sum(quantity),0) FROM confirmation WHERE schedule_id=? AND status IN ('EFFECTIVE','SUBMITTED')",BigDecimal.class,r.schedule());CollaborationRules.capacity(r.ordered(),reserved,q);
   boolean late=input.promisedDate().isAfter(r.requestedDate());if(late && (input.reason()==null || input.reason().isBlank())) throw new ApiProblem(422,"DELAY_REASON_REQUIRED");
   UUID id=UUID.randomUUID();db.update("INSERT INTO confirmation(id,deployment_id,order_id,revision_id,schedule_id,source_revision,quantity,promised_date,status,requester_id) SELECT ?,deployment_id,?,?,?,?,?,?,?,? FROM app_guard WHERE id=1",id,r.order(),r.revision(),r.schedule(),r.sourceRevision(),q,input.promisedDate(),late?"SUBMITTED":"EFFECTIVE",actor.id());
   if(late) insertChange(r,id,"INITIAL",r.requestedDate(),input.promisedDate(),input.reason(),actor.id(),0);else plan(r,id,0,q,input.promisedDate());
   emit(r,id,0,late?"confirmation.submitted":"confirmation.effective");bump(r);var value=confirmation(actor,id);return outcome(value,id,value.version(),r);
  });
 }
 private void unshipped(UUID confirmation) {if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN delivery_plan p ON p.id=l.plan_id WHERE p.confirmation_id=? AND h.status='SUBMITTED')",Boolean.class,confirmation))) throw new ApiProblem(409,"PLAN_ALREADY_SHIPPED");}
 public IdentityService.StoredResult requestChange(SessionUser actor,UUID order,String key,String etag,ChangeInput input) {
  return commands.execute(actor,"confirmation.submit","delivery-change.create:"+order,key,List.of(etag,input),()->{var c=confirmation(actor,input.confirmationId());supplier(actor,order,c.scheduleId(),input.sourceRevision());},body->{},()->{
   var c=confirmation(actor,input.confirmationId());Ref r=supplier(actor,order,c.scheduleId(),input.sourceRevision());IdentityCommands.match(r.orderVersion(),etag);
   if(!c.status().equals("EFFECTIVE")) throw new ApiProblem(409,"CONFIRMATION_NOT_EFFECTIVE");if(c.promisedDate().equals(input.proposedDate().toString())) throw new ApiProblem(422,"DATE_UNCHANGED");
   if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM delivery_change WHERE confirmation_id=? AND status='SUBMITTED')",Boolean.class,c.id()))) throw new ApiProblem(409,"CHANGE_ALREADY_PENDING");
   unshipped(c.id());
   UUID id=insertChange(r,c.id(),"RESCHEDULE",LocalDate.parse(c.promisedDate()),input.proposedDate(),input.reason(),actor.id(),c.version());bump(r);var value=change(actor,id);return outcome(value,id,value.version(),r);
  });
 }
 private Ref approver(SessionUser actor,UUID id) {var d=change(actor,id);var r=ref(actor,d.scheduleId());CollaborationRules.independentApprover(actor.id(),d.requesterId());var ctx=policy.context(actor,"delivery.approve");if(ctx.supplierUser()) throw new ApiProblem(403,"FORBIDDEN");actionScope(actor,r,AccessPolicy.Permission.APPROVE,"delivery.approve");live(r,d.sourceRevision());return r;}
 public IdentityService.StoredResult decide(SessionUser actor,UUID id,String key,String etag,Decision input) {
  return commands.execute(actor,"delivery.approve","delivery-change.decide:"+id,key,List.of(etag,input),()->approver(actor,id),body->{},()->{
   var d=change(actor,id);Ref r=approver(actor,id);IdentityCommands.match(d.version(),etag);if(!d.status().equals("SUBMITTED")) throw new ApiProblem(409,"CHANGE_NOT_PENDING");var c=confirmation(actor,d.confirmationId());
   long base=db.queryForObject("SELECT base_confirmation_version FROM delivery_change WHERE id=?",Long.class,id);if(c.version()!=base) throw new ApiProblem(409,"CONFIRMATION_VERSION_CONFLICT");
   boolean approve=input.decision().equals("APPROVED"),initial=d.kind().equals("INITIAL");if(!(initial?c.status().equals("SUBMITTED"):c.status().equals("EFFECTIVE"))) throw new ApiProblem(409,"CONFIRMATION_STATE_CONFLICT");
   if(approve && !initial) unshipped(c.id());
   if(approve || initial) {db.update("UPDATE confirmation SET promised_date=?,status=?,lock_version=lock_version+1 WHERE id=?",approve?LocalDate.parse(d.proposedDate()):LocalDate.parse(c.promisedDate()),approve?"EFFECTIVE":"REJECTED",c.id());}
   if(approve) {db.update("UPDATE delivery_plan SET status='SUPERSEDED',lock_version=lock_version+1 WHERE confirmation_id=? AND status='PLANNED'",c.id());plan(r,c.id(),c.version()+1,new BigDecimal(c.quantity()),LocalDate.parse(d.proposedDate()));emit(r,c.id(),c.version()+1,"confirmation.effective");}
   if(!approve && initial) emit(r,c.id(),c.version()+1,"confirmation.rejected");
   db.update("UPDATE delivery_change SET status=?,lock_version=lock_version+1 WHERE id=?",input.decision(),id);
   db.update("INSERT INTO approval_event(id,deployment_id,change_id,actor_id,decision,reason) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1",UUID.randomUUID(),id,actor.id(),input.decision(),input.reason().trim());
   emit(r,id,d.version()+1,approve?"delivery-change.approved":"delivery-change.rejected");bump(r);var value=change(actor,id);return outcome(value,id,value.version(),r);
  });
 }
 public IdentityService.StoredResult withdraw(SessionUser actor,UUID id,String key,String etag) {
  return commands.execute(actor,"confirmation.submit","delivery-change.withdraw:"+id,key,etag,()->{var d=change(actor,id);supplier(actor,d.orderId(),d.scheduleId(),d.sourceRevision());if(!d.requesterId().equals(actor.id())) throw new ApiProblem(403,"REQUESTER_REQUIRED");},body->{},()->{
   var d=change(actor,id);Ref r=supplier(actor,d.orderId(),d.scheduleId(),d.sourceRevision());IdentityCommands.match(d.version(),etag);if(!d.status().equals("SUBMITTED")) throw new ApiProblem(409,"CHANGE_NOT_PENDING");
   if(d.kind().equals("INITIAL")) {var c=confirmation(actor,d.confirmationId());db.update("UPDATE confirmation SET status='REJECTED',lock_version=lock_version+1 WHERE id=?",c.id());emit(r,c.id(),c.version()+1,"confirmation.rejected");}
   db.update("UPDATE delivery_change SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?",id);emit(r,id,d.version()+1,"delivery-change.withdrawn");bump(r);var value=change(actor,id);return outcome(value,id,value.version(),r);
  });
 }
}
