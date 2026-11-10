package org.opensrm.shipping;
import java.util.*;
import java.math.BigDecimal;
import jakarta.validation.Valid;
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
public class ShippingService {
 public record LineInput(@NotNull UUID planId,@NotBlank @Size(max=80) String sourceRevision,@Min(0) long orderVersion,@NotNull @Pattern(regexp="[0-9]{1,13}(?:[.][0-9]{1,6})?") String quantity) { }
 public record Input(@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9_-]+") String reference,@NotEmpty @Size(max=50) List<@NotNull @Valid LineInput> lines) { }
 public record Line(UUID id,int lineNumber,UUID planId,UUID orderId,UUID scheduleId,String sourceRevision,long orderVersion,String quantity) { }
 public record Asn(UUID id,String reference,String status,long version,List<Line> lines) { }
 public record Balance(UUID scheduleId,String submittedQuantity,String receivedQuantity,String inTransitQuantity,String availableQuantity) { }
 private final JdbcTemplate db;private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;private final IdentityCommands commands;private final OrderService orders;private final tools.jackson.databind.ObjectMapper json;
 public ShippingService(JdbcTemplate db,AccessPolicy policy,IdentityCommands commands,OrderService orders,tools.jackson.databind.ObjectMapper json) {this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.commands=commands;this.orders=orders;this.json=json;}
 private static final String JOIN="po_schedule s JOIN po_item i ON i.id=s.item_id JOIN po_revision r ON r.id=i.revision_id JOIN purchase_order o ON o.id=r.order_id";
 private String scope(AccessPolicy.Context c) {return orders.visible(c)+" AND EXISTS(SELECT 1 FROM plant ap WHERE ap.id=i.plant_id AND ap.active) AND "+policy.grant("o.company_id","i.plant_id","o.purchasing_org_id",AccessPolicy.Permission.ORDER);}
 private Map<String,Object> ref(SessionUser actor,UUID schedule,boolean write) {
  var c=policy.context(actor,write?"asn.manage":"order.read");String where=scope(c);
  if(write) {if(!c.supplierUser() || c.supplierId()==null) throw new ApiProblem(403,"SUPPLIER_IDENTITY_REQUIRED");where+=" AND "+policy.grant("o.company_id","i.plant_id","o.purchasing_org_id",AccessPolicy.Permission.ASN);}
  var rows=sql.queryForList("SELECT o.*,s.id schedule_id,s.ordered_quantity,i.external_item,s.external_schedule,r.id revision_id FROM "+JOIN+" WHERE s.id=:schedule AND "+where,policy.parameters(c).addValue("schedule",schedule));
  if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
 }
 private void live(Map<String,Object> r,String source) {
  if(!r.get("revision_id").equals(r.get("current_revision_id")) || !r.get("source_revision").equals(source)) throw new ApiProblem(409,"SOURCE_REVISION_CONFLICT");
  if(!r.get("lifecycle").equals("ACTIVE")) throw new ApiProblem(409,"ORDER_NOT_ACTIVE");
  if((Boolean)r.get("consistency_hold")) throw new ApiProblem(409,"CONSISTENCY_HOLD");
  if(!db.queryForObject("SELECT status FROM supplier WHERE id=?",String.class,r.get("supplier_id")).equals("ACTIVE")) throw new ApiProblem(409,"SUPPLIER_FROZEN");
 }
 public BigDecimal received(UUID order,String item,String schedule) {return db.queryForObject("SELECT COALESCE(sum(CASE e.event_type WHEN 'POSTED' THEN e.quantity ELSE -e.quantity END),0) FROM receipt_event e JOIN po_schedule s ON s.id=e.schedule_id JOIN po_item i ON i.id=s.item_id WHERE e.order_id=? AND i.external_item=? AND s.external_schedule=?",BigDecimal.class,order,item,schedule);}
 public BigDecimal allocated(UUID line) {return db.queryForObject("SELECT COALESCE(sum(CASE e.event_type WHEN 'POSTED' THEN a.quantity ELSE -a.quantity END),0) FROM receipt_allocation a JOIN receipt_event e ON e.id=a.event_id WHERE a.asn_line_id=?",BigDecimal.class,line);}
 public BigDecimal transit(UUID order,String item,String schedule) {return ShippingQuantities.transit(submitted(order,item,schedule),allocated(order,item,schedule));}
 private BigDecimal submitted(UUID order,String item,String schedule) {return db.queryForObject("SELECT COALESCE(sum(l.quantity),0) FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN po_schedule s ON s.id=l.schedule_id JOIN po_item i ON i.id=s.item_id WHERE h.status='SUBMITTED' AND l.order_id=? AND i.external_item=? AND s.external_schedule=?",BigDecimal.class,order,item,schedule);}
 private BigDecimal allocated(UUID order,String item,String schedule) {return db.queryForObject("SELECT COALESCE(sum(CASE e.event_type WHEN 'POSTED' THEN a.quantity ELSE -a.quantity END),0) FROM receipt_allocation a JOIN receipt_event e ON e.id=a.event_id JOIN asn_line l ON l.id=a.asn_line_id JOIN po_schedule s ON s.id=l.schedule_id JOIN po_item i ON i.id=s.item_id WHERE l.order_id=? AND i.external_item=? AND s.external_schedule=?",BigDecimal.class,order,item,schedule);}
 private Balance balance(Map<String,Object> r) {
  UUID order=(UUID)r.get("id"),schedule=(UUID)r.get("schedule_id");String item=(String)r.get("external_item"),key=(String)r.get("external_schedule");
  BigDecimal shipped=submitted(order,item,key),net=received(order,item,key),transit=ShippingQuantities.transit(shipped,allocated(order,item,key));
  BigDecimal confirmed=db.queryForObject("SELECT COALESCE(sum(quantity),0) FROM confirmation WHERE schedule_id=? AND status='EFFECTIVE'",BigDecimal.class,schedule);
  return new Balance(schedule,shipped.toPlainString(),net.toPlainString(),transit.toPlainString(),ShippingQuantities.available((BigDecimal)r.get("ordered_quantity"),confirmed,net,transit).toPlainString());
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public List<Balance> balances(SessionUser actor,UUID order) {var v=orders.get(actor,order);return v.items().stream().flatMap(i->i.schedules().stream()).map(s->balance(ref(actor,s.id(),false))).toList();}
 private List<Line> lines(UUID id) {return db.query("SELECT * FROM asn_line WHERE asn_id=? ORDER BY line_number",(r,n)->new Line(r.getObject("id",UUID.class),r.getInt("line_number"),r.getObject("plan_id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("schedule_id",UUID.class),r.getString("source_revision"),r.getLong("order_version"),r.getBigDecimal("quantity").toPlainString()),id);}
 private Asn raw(UUID id) {var rows=db.query("SELECT * FROM asn WHERE id=?",(r,n)->new Asn(id,r.getString("reference"),r.getString("status"),r.getLong("lock_version"),List.of()),id);if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");var h=rows.getFirst();return new Asn(id,h.reference(),h.status(),h.version(),lines(id));}
 public Asn get(SessionUser actor,UUID id) {var h=raw(id);for(var l:h.lines()) ref(actor,l.scheduleId(),false);return h;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Object list(SessionUser actor,int page,int size) {
  var c=policy.context(actor,"order.read");String predicate="h.deployment_id="+AccessPolicy.REALM+" AND EXISTS(SELECT 1 FROM asn_line al WHERE al.asn_id=h.id) AND NOT EXISTS(SELECT 1 FROM asn_line al JOIN "+JOIN+" ON s.id=al.schedule_id WHERE al.asn_id=h.id AND NOT COALESCE(("+scope(c)+"),false))";
  var args=policy.parameters(c).addValue("limit",size).addValue("offset",(long)page*size);var ids=sql.queryForList("SELECT h.id FROM asn h WHERE "+predicate+" ORDER BY h.created_at DESC,h.id LIMIT :limit OFFSET :offset",args,UUID.class);
  return new IdentityService.Page<>(ids.stream().map(this::raw).toList(),sql.queryForObject("SELECT count(*) FROM asn h WHERE "+predicate,args,Long.class),page,size);
 }
 private List<Map<String,Object>> validate(SessionUser actor,Input input) {
  if(input.lines().stream().map(LineInput::planId).distinct().count()!=input.lines().size()) throw new ApiProblem(422,"DUPLICATE_PLAN");
  var refs=new ArrayList<Map<String,Object>>();UUID company=null,supplier=null;
  for(var l:input.lines()) {var plans=db.queryForList("SELECT * FROM delivery_plan WHERE id=?",l.planId());if(plans.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");var p=plans.getFirst();var r=ref(actor,(UUID)p.get("schedule_id"),true);live(r,l.sourceRevision());
   if(!Set.of("PLANNED","PARTIALLY_SHIPPED").contains(p.get("status")) || !p.get("source_revision").equals(l.sourceRevision())) throw new ApiProblem(409,"PLAN_NOT_ACTIVE");
   if(company!=null && (!company.equals(r.get("company_id")) || !supplier.equals(r.get("supplier_id")))) throw new ApiProblem(422,"ASN_SCOPE_MISMATCH");company=(UUID)r.get("company_id");supplier=(UUID)r.get("supplier_id");ShippingQuantities.positive(new BigDecimal(l.quantity()));refs.add(r);
  }return refs;
 }
 private void authorize(SessionUser actor,Asn h) {for(var l:h.lines()) {var r=ref(actor,l.scheduleId(),true);live(r,l.sourceRevision());}}
 private IdentityCommands.Outcome outcome(SessionUser actor,UUID id) {var h=get(actor,id);var r=ref(actor,h.lines().getFirst().scheduleId(),false);return new IdentityCommands.Outcome(h,id,h.version(),"COLLABORATION",(UUID)r.get("supplier_id"),List.of((UUID)r.get("company_id")));}
 private void insertLines(UUID id,Input input) {int n=0;for(var l:input.lines()) db.update("INSERT INTO asn_line(id,deployment_id,asn_id,line_number,plan_id,order_id,schedule_id,source_revision,order_version,quantity) SELECT ?,deployment_id,?,?,id,order_id,schedule_id,?,?,? FROM delivery_plan WHERE id=?",UUID.randomUUID(),id,++n,l.sourceRevision(),l.orderVersion(),new BigDecimal(l.quantity()),l.planId());}
 public IdentityService.StoredResult create(SessionUser actor,String key,Input input) {return commands.execute(actor,"asn.manage","asn.create",key,input,()->validate(actor,input),v->get(actor,UUID.fromString(json.readTree(v).get("id").asString())),()->{
  var refs=validate(actor,input);var r=refs.getFirst();if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM asn WHERE company_id=? AND supplier_id=? AND reference=?)",Boolean.class,r.get("company_id"),r.get("supplier_id"),input.reference()))) throw new ApiProblem(409,"ASN_REFERENCE_EXISTS");
  UUID id=UUID.randomUUID();db.update("INSERT INTO asn(id,deployment_id,company_id,supplier_id,reference,status,requester_id) SELECT ?,deployment_id,?,?,?,'DRAFT',? FROM app_guard WHERE id=1",id,r.get("company_id"),r.get("supplier_id"),input.reference(),actor.id());insertLines(id,input);return outcome(actor,id);
 });}
 public IdentityService.StoredResult update(SessionUser actor,UUID id,String key,String etag,Input input) {return commands.execute(actor,"asn.manage","asn.update:"+id,key,List.of(etag,input),()->{authorize(actor,get(actor,id));validate(actor,input);},v->{},()->{
  var h=get(actor,id);IdentityCommands.match(h.version(),etag);if(!h.status().equals("DRAFT")) throw new ApiProblem(409,"ASN_NOT_DRAFT");if(!h.reference().equals(input.reference())) throw new ApiProblem(422,"ASN_REFERENCE_IMMUTABLE");
  var old=ref(actor,h.lines().getFirst().scheduleId(),true);var next=validate(actor,input).getFirst();if(!old.get("company_id").equals(next.get("company_id")) || !old.get("supplier_id").equals(next.get("supplier_id"))) throw new ApiProblem(422,"ASN_SCOPE_MISMATCH");
  db.update("DELETE FROM asn_line WHERE asn_id=?",id);insertLines(id,input);db.update("UPDATE asn SET lock_version=lock_version+1 WHERE id=?",id);return outcome(actor,id);
 });}
 public IdentityService.StoredResult transition(SessionUser actor,UUID id,String key,String etag,boolean cancel) {return commands.execute(actor,"asn.manage",(cancel?"asn.cancel:":"asn.submit:")+id,key,etag,()->authorize(actor,get(actor,id)),v->{},()->{
  var h=get(actor,id);IdentityCommands.match(h.version(),etag);if(cancel) {
   if(!Set.of("DRAFT","SUBMITTED").contains(h.status())) throw new ApiProblem(409,"ASN_STATE_CONFLICT");
   if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM receipt_allocation a JOIN asn_line l ON l.id=a.asn_line_id WHERE l.asn_id=?)",Boolean.class,id))) throw new ApiProblem(409,"ASN_HAS_RECEIPTS");
  } else {
   if(!h.status().equals("DRAFT")) throw new ApiProblem(409,"ASN_NOT_DRAFT");
   for(var l:h.lines()) {var r=ref(actor,l.scheduleId(),true);IdentityCommands.match(((Number)r.get("lock_version")).longValue(),"\""+l.orderVersion()+"\"");
    var p=db.queryForMap("SELECT * FROM delivery_plan WHERE id=?",l.planId());if(!Set.of("PLANNED","PARTIALLY_SHIPPED").contains(p.get("status"))) throw new ApiProblem(409,"PLAN_NOT_ACTIVE");
    var b=balance(r);BigDecimal used=db.queryForObject("SELECT COALESCE(sum(l.quantity),0) FROM asn_line l JOIN asn h ON h.id=l.asn_id WHERE l.plan_id=? AND h.status='SUBMITTED'",BigDecimal.class,l.planId());
    // Include earlier lines in this same draft that share a logical schedule.
    BigDecimal same=h.lines().stream().filter(x->x.scheduleId().equals(l.scheduleId()) && x.lineNumber()<l.lineNumber()).map(x->new BigDecimal(x.quantity())).reduce(BigDecimal.ZERO,BigDecimal::add);
    ShippingQuantities.shipment(new BigDecimal(l.quantity()),new BigDecimal(b.availableQuantity()).subtract(same).max(BigDecimal.ZERO),(BigDecimal)p.get("quantity"),used);
   }
  }
  db.update("UPDATE asn SET status=?,lock_version=lock_version+1 WHERE id=?",cancel?"CANCELLED":"SUBMITTED",id);
  for(var l:h.lines()) {
   BigDecimal used=db.queryForObject("SELECT COALESCE(sum(l.quantity),0) FROM asn_line l JOIN asn h ON h.id=l.asn_id WHERE l.plan_id=? AND h.status='SUBMITTED'",BigDecimal.class,l.planId());BigDecimal planned=db.queryForObject("SELECT quantity FROM delivery_plan WHERE id=?",BigDecimal.class,l.planId());
   db.update("UPDATE delivery_plan SET status=?,lock_version=lock_version+1 WHERE id=?",used.signum()==0?"PLANNED":used.compareTo(planned)==0?"SHIPPED":"PARTIALLY_SHIPPED",l.planId());
  }
  for(var l:h.lines()) db.update("INSERT INTO outbox_event(id,deployment_id,order_id,aggregate_id,aggregate_version,source_revision,event_type,status) SELECT ?,deployment_id,?,?,?,?,?,'PENDING' FROM app_guard WHERE id=1",UUID.randomUUID(),l.orderId(),l.id(),h.version()+1,l.sourceRevision(),cancel?"asn.cancelled":"asn.submitted");
  for(UUID order:h.lines().stream().map(Line::orderId).distinct().toList()) db.update("UPDATE purchase_order SET lock_version=lock_version+1 WHERE id=?",order);
  return outcome(actor,id);
 });}
 public Object receipts(SessionUser actor,int page,int size) {var c=policy.context(actor,"order.read");var args=policy.parameters(c).addValue("limit",size).addValue("offset",(long)page*size);String from="receipt_event e JOIN "+JOIN+" ON s.id=e.schedule_id WHERE "+scope(c);return new IdentityService.Page<>(sql.queryForList("SELECT e.id,e.external_key,e.order_id,i.external_item,s.external_schedule,e.event_type,e.original_id,e.quantity::text,e.posting_date,CASE WHEN e.event_type='REVERSAL' THEN 'REVERSAL' WHEN COALESCE((SELECT sum(rv.quantity) FROM receipt_event rv WHERE rv.original_id=e.id),0)=e.quantity THEN 'REVERSED' WHEN EXISTS(SELECT 1 FROM receipt_event rv WHERE rv.original_id=e.id) THEN 'PARTIALLY_REVERSED' ELSE 'POSTED' END receipt_status FROM "+from+" ORDER BY e.posting_date DESC,e.id LIMIT :limit OFFSET :offset",args),sql.queryForObject("SELECT count(*) FROM "+from,args,Long.class),page,size);}

 public Object receipt(SessionUser actor,UUID id) {
  var c=policy.context(actor,"order.read");var rows=sql.queryForList("SELECT e.id,e.external_key,e.event_type,e.original_id,e.quantity::text,e.posting_date FROM receipt_event e JOIN "+JOIN+" ON s.id=e.schedule_id WHERE e.id=:id AND "+scope(c),policy.parameters(c).addValue("id",id));if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");var event=new LinkedHashMap<>(rows.getFirst());event.put("allocations",db.queryForList("SELECT h.reference,l.line_number,a.quantity::text FROM receipt_allocation a JOIN asn_line l ON l.id=a.asn_line_id JOIN asn h ON h.id=l.asn_id WHERE a.event_id=? ORDER BY h.reference,l.line_number",id));return event;
 }
}

