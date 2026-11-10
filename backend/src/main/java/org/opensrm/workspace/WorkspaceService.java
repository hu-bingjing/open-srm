package org.opensrm.workspace;
import java.util.*;
import org.opensrm.access.AccessPolicy;
import org.opensrm.identity.*;
import org.opensrm.purchasing.OrderService;
import org.opensrm.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WorkspaceService {
 public record Filter(UUID company,UUID plant,UUID purchasingOrg,UUID supplier) { }
 public record Task(UUID id,UUID orderId,String externalOrder,String kind,String dueDate) { }
 public record Dashboard(long orders,long heldOrders,long unconfirmedSchedules,long pendingApprovals,long overduePlans,long failedSyncJobs,long unreadNotifications,List<Task> tasks) { }
 public record Statistic(UUID companyId,String companyCode,UUID plantId,String plantCode,String unit,String orderedQuantity,String effectiveQuantity,String pendingQuantity,String submittedQuantity,String netReceiptQuantity,String inTransitQuantity) { }
 private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;private final OrderService orders;private final NotificationService notifications;
 public WorkspaceService(JdbcTemplate db,AccessPolicy policy,OrderService orders,NotificationService notifications) {sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.orders=orders;this.notifications=notifications;}
 private String cte(AccessPolicy.Context ctx,Filter f,MapSqlParameterSource args) {
  String scope=orders.visible(ctx)+" AND p.active AND "+policy.grant("o.company_id","i.plant_id","o.purchasing_org_id",AccessPolicy.Permission.ORDER);
  if(f.company()!=null){scope+=" AND o.company_id=:company";args.addValue("company",f.company());}
  if(f.plant()!=null){scope+=" AND i.plant_id=:plant";args.addValue("plant",f.plant());}
  if(f.purchasingOrg()!=null){scope+=" AND o.purchasing_org_id=:porg";args.addValue("porg",f.purchasingOrg());}
  if(f.supplier()!=null){scope+=" AND o.supplier_id=:supplier";args.addValue("supplier",f.supplier());}
  return "WITH lines AS (SELECT o.id order_id,o.external_id,o.company_id,o.purchasing_org_id,o.supplier_id,o.lifecycle,o.consistency_hold,o.current_revision_id,r.id revision_id,i.plant_id,i.unit,s.id schedule_id,s.ordered_quantity,s.requested_date,c.code company_code,c.timezone,p.code plant_code FROM purchase_order o JOIN po_revision r ON r.order_id=o.id JOIN po_item i ON i.revision_id=r.id JOIN po_schedule s ON s.item_id=i.id JOIN company c ON c.id=o.company_id JOIN plant p ON p.id=i.plant_id WHERE "+scope+") ";
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public List<Statistic> statistics(SessionUser actor,Filter filter) {
  var ctx=policy.context(actor,"order.read");var args=policy.parameters(ctx);String cte=cte(ctx,filter,args);
  String columns="l.company_id,l.company_code,l.plant_id,l.plant_code,l.unit";
  // Each ledger is aggregated independently before joining, avoiding fan-out multiplication.
  String query=cte+", quantities AS (SELECT "+columns+",CASE WHEN l.revision_id=l.current_revision_id THEN l.ordered_quantity ELSE 0 END ordered,COALESCE((SELECT sum(c.quantity) FROM confirmation c WHERE c.schedule_id=l.schedule_id AND l.revision_id=l.current_revision_id AND c.status='EFFECTIVE'),0) effective,COALESCE((SELECT sum(c.quantity) FROM confirmation c WHERE c.schedule_id=l.schedule_id AND l.revision_id=l.current_revision_id AND c.status='SUBMITTED'),0) pending,COALESCE((SELECT sum(a.quantity) FROM asn_line a JOIN asn h ON h.id=a.asn_id WHERE a.schedule_id=l.schedule_id AND h.status='SUBMITTED'),0) submitted,COALESCE((SELECT sum(CASE e.event_type WHEN 'POSTED' THEN e.quantity ELSE -e.quantity END) FROM receipt_event e WHERE e.schedule_id=l.schedule_id),0) received,COALESCE((SELECT sum(GREATEST(a.quantity-COALESCE((SELECT sum(CASE e.event_type WHEN 'POSTED' THEN ra.quantity ELSE -ra.quantity END) FROM receipt_allocation ra JOIN receipt_event e ON e.id=ra.event_id WHERE ra.asn_line_id=a.id),0),0)) FROM asn_line a JOIN asn h ON h.id=a.asn_id WHERE a.schedule_id=l.schedule_id AND h.status='SUBMITTED'),0) transit FROM lines l) SELECT company_id,company_code,plant_id,plant_code,unit,sum(ordered) ordered,sum(effective) effective,sum(pending) pending,sum(submitted) submitted,sum(received) received,sum(transit) transit FROM quantities GROUP BY company_id,company_code,plant_id,plant_code,unit ORDER BY company_code,plant_code,unit";
  return sql.query(query,args,(r,n)->new Statistic(r.getObject("company_id",UUID.class),r.getString("company_code"),r.getObject("plant_id",UUID.class),r.getString("plant_code"),r.getString("unit"),r.getBigDecimal("ordered").toPlainString(),r.getBigDecimal("effective").toPlainString(),r.getBigDecimal("pending").toPlainString(),r.getBigDecimal("submitted").toPlainString(),r.getBigDecimal("received").toPlainString(),r.getBigDecimal("transit").toPlainString()));
 }
 private long count(String cte,String query,MapSqlParameterSource args) {return sql.queryForObject(cte+query,args,Long.class);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Dashboard dashboard(SessionUser actor) {
  var ctx=policy.context(actor,null);var args=policy.parameters(ctx);long orderCount=0,held=0,unconfirmed=0,pending=0,overdue=0,failed=0;var tasks=new ArrayList<Task>();
  if(ctx.permissions().contains("order.read")) {
   String cte=cte(ctx,new Filter(null,null,null,null),args);
   orderCount=count(cte,"SELECT count(DISTINCT order_id) FROM lines WHERE revision_id=current_revision_id",args);
   held=count(cte,"SELECT count(DISTINCT order_id) FROM lines WHERE revision_id=current_revision_id AND consistency_hold",args);
   String live="l.revision_id=l.current_revision_id AND l.lifecycle='ACTIVE' AND NOT l.consistency_hold AND EXISTS(SELECT 1 FROM supplier sp WHERE sp.id=l.supplier_id AND sp.status='ACTIVE')";
   if(ctx.supplierUser()&&ctx.permissions().contains("confirmation.submit")) {
    String where=live+" AND "+policy.grant("l.company_id","l.plant_id","l.purchasing_org_id",AccessPolicy.Permission.CONFIRM)+" AND l.ordered_quantity>COALESCE((SELECT sum(c.quantity) FROM confirmation c WHERE c.schedule_id=l.schedule_id AND c.status IN ('EFFECTIVE','SUBMITTED')),0)";
    unconfirmed=count(cte,"SELECT count(*) FROM lines l WHERE "+where,args);tasks.addAll(sql.query(cte+"SELECT l.schedule_id id,l.order_id,l.external_id,l.requested_date due FROM lines l WHERE "+where+" ORDER BY l.requested_date,l.schedule_id LIMIT 10",args,(r,n)->task(r,"CONFIRM")));
   }
   if(!ctx.supplierUser()&&ctx.permissions().contains("delivery.approve")) {
    String where=live+" AND x.status='SUBMITTED' AND x.requester_id<>:actor AND "+policy.grant("l.company_id","l.plant_id","l.purchasing_org_id",AccessPolicy.Permission.APPROVE);
    pending=count(cte,"SELECT count(*) FROM lines l JOIN confirmation c ON c.schedule_id=l.schedule_id JOIN delivery_change x ON x.confirmation_id=c.id WHERE "+where,args);
    tasks.addAll(sql.query(cte+"SELECT x.id,l.order_id,l.external_id,x.proposed_date due FROM lines l JOIN confirmation c ON c.schedule_id=l.schedule_id JOIN delivery_change x ON x.confirmation_id=c.id WHERE "+where+" ORDER BY x.created_at,x.id LIMIT 10",args,(r,n)->task(r,"APPROVE")));
   }
   String action=ctx.supplierUser()?(ctx.permissions().contains("asn.manage")?policy.grant("l.company_id","l.plant_id","l.purchasing_org_id",AccessPolicy.Permission.ASN):"FALSE"):"TRUE";
   String where=live+" AND "+action+" AND x.status IN ('PLANNED','PARTIALLY_SHIPPED') AND x.due_date<(CURRENT_TIMESTAMP AT TIME ZONE l.timezone)::date AND x.quantity>COALESCE((SELECT sum(a.quantity) FROM asn_line a JOIN asn h ON h.id=a.asn_id WHERE a.plan_id=x.id AND h.status='SUBMITTED'),0)";
   overdue=count(cte,"SELECT count(*) FROM lines l JOIN delivery_plan x ON x.schedule_id=l.schedule_id WHERE "+where,args);
   tasks.addAll(sql.query(cte+"SELECT x.id,l.order_id,l.external_id,x.due_date due FROM lines l JOIN delivery_plan x ON x.schedule_id=l.schedule_id WHERE "+where+" ORDER BY x.due_date,x.id LIMIT 10",args,(r,n)->task(r,"OVERDUE")));
  }
  if(ctx.permissions().contains("sync.read")&&!ctx.supplierUser())failed=sql.queryForObject("SELECT count(*) FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE j.status='FAILED' AND c.owner_id=:actor AND c.deployment_id="+AccessPolicy.REALM+" AND "+policy.currentActor()+" AND "+policy.grant("c.company_id","NULL","NULL",AccessPolicy.Permission.SYNC),args,Long.class);
  return new Dashboard(orderCount,held,unconfirmed,pending,overdue,failed,notifications.unread(actor),List.copyOf(tasks));
 }
 private static Task task(java.sql.ResultSet r,String kind) throws java.sql.SQLException {return new Task(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getString("external_id"),kind,r.getDate("due").toString());}
}
