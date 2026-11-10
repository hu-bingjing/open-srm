package org.opensrm.notification;

import java.util.*;
import org.opensrm.access.AccessPolicy;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.*;
import org.opensrm.purchasing.OrderService;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
 public record Notice(UUID id,String eventType,String externalOrder,UUID orderId,UUID jobId,String createdAt,String readAt,long version) { }
 public record Delivery(UUID id,String eventType,UUID orderId,UUID jobId,String status,int attempts,String errorCode,long version) { }
 private final JdbcTemplate db;private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;private final OrderService orders;private final IdentityCommands commands;private final TransactionTemplate tx;
 public NotificationService(JdbcTemplate db,AccessPolicy policy,OrderService orders,IdentityCommands commands,org.springframework.transaction.PlatformTransactionManager manager) {this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.orders=orders;this.commands=commands;this.tx=new TransactionTemplate(manager);}
 public String visible(AccessPolicy.Context ctx) {
  // Pending/superseded deliveries may not have cached a plant yet. Resolve their
  // immutable business reference before applying list/count/retry authorization.
  String schedule="(SELECT CASE WHEN e.event_type LIKE 'confirmation.%' THEN (SELECT x.schedule_id FROM confirmation x WHERE x.id=e.aggregate_id AND x.order_id=e.order_id) WHEN e.event_type LIKE 'delivery-plan.%' THEN (SELECT x.schedule_id FROM delivery_plan x WHERE x.id=e.aggregate_id AND x.order_id=e.order_id) WHEN e.event_type LIKE 'delivery-change.%' THEN (SELECT c.schedule_id FROM delivery_change x JOIN confirmation c ON c.id=x.confirmation_id WHERE x.id=e.aggregate_id AND x.order_id=e.order_id) WHEN e.event_type LIKE 'asn.%' THEN (SELECT x.schedule_id FROM asn_line x WHERE x.id=e.aggregate_id AND x.order_id=e.order_id) END FROM outbox_event e WHERE d.origin_kind='COLLABORATION' AND e.id=d.origin_id AND e.order_id=d.order_id)";
  String plant="COALESCE(d.plant_id,(SELECT si.plant_id FROM po_schedule ss JOIN po_item si ON si.id=ss.item_id WHERE ss.id="+schedule+"))";
  String lineScope="EXISTS(SELECT 1 FROM plant np WHERE np.id="+plant+" AND np.company_id=o.company_id AND np.active) AND "+policy.grant("o.company_id",plant,"o.purchasing_org_id",AccessPolicy.Permission.ORDER);
  String order=ctx.permissions().contains("order.read")?"EXISTS(SELECT 1 FROM purchase_order o WHERE o.id=d.order_id AND "+orders.visible(ctx)+" AND (d.origin_kind='ORDER' OR d.event_type='order.collaboration.superseded' OR ("+lineScope+")))":"FALSE";
  String sync=ctx.permissions().contains("sync.read")&&!ctx.supplierUser()?"EXISTS(SELECT 1 FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE j.id=d.job_id AND c.owner_id=:actor AND c.deployment_id="+AccessPolicy.REALM+" AND "+policy.grant("c.company_id","NULL","NULL",AccessPolicy.Permission.SYNC)+")":"FALSE";
  return "d.deployment_id="+AccessPolicy.REALM+" AND "+policy.currentActor()+" AND ((d.order_id IS NOT NULL AND "+order+") OR (d.job_id IS NOT NULL AND "+sync+"))";
 }
 private static final String FROM="notification n JOIN notification_delivery d ON d.id=n.delivery_id";
 private static final RowMapper<Notice> NOTICE=(r,n)->new Notice(r.getObject("id",UUID.class),r.getString("event_type"),r.getString("external_order"),r.getObject("order_id",UUID.class),r.getObject("job_id",UUID.class),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant().toString(),r.getLong("lock_version"));
 private static final RowMapper<Delivery> DELIVERY=(r,n)->new Delivery(r.getObject("id",UUID.class),r.getString("event_type"),r.getObject("order_id",UUID.class),r.getObject("job_id",UUID.class),r.getString("status"),r.getInt("attempts"),r.getString("error_code"),r.getLong("lock_version"));
 private String own(AccessPolicy.Context ctx) {return "n.recipient_id=:actor AND "+visible(ctx);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public IdentityService.Page<Notice> list(SessionUser actor,int page,int size,boolean unread) {
  var ctx=policy.context(actor,null);var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);String where=own(ctx)+(unread?" AND n.read_at IS NULL":"");
  return new IdentityService.Page<>(sql.query("SELECT n.*,d.event_type,d.order_id,d.job_id,(SELECT external_id FROM purchase_order no WHERE no.id=d.order_id) external_order FROM "+FROM+" WHERE "+where+" ORDER BY n.created_at DESC,n.id LIMIT :limit OFFSET :offset",args,NOTICE),sql.queryForObject("SELECT count(*) FROM "+FROM+" WHERE "+where,args,Long.class),page,size);
 }
 public long unread(SessionUser actor) {var ctx=policy.context(actor,null);return sql.queryForObject("SELECT count(*) FROM "+FROM+" WHERE n.read_at IS NULL AND "+own(ctx),policy.parameters(ctx),Long.class);}
 public Notice get(SessionUser actor,UUID id) {
  var ctx=policy.context(actor,null);var rows=sql.query("SELECT n.*,d.event_type,d.order_id,d.job_id,(SELECT external_id FROM purchase_order no WHERE no.id=d.order_id) external_order FROM "+FROM+" WHERE n.id=:id AND "+own(ctx),policy.parameters(ctx).addValue("id",id),NOTICE);
  if(rows.isEmpty())throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
 }
 public IdentityService.StoredResult read(SessionUser actor,UUID id,String key,String etag) {
  return commands.execute(actor,null,"notification.read:"+id,key,etag,()->get(actor,id),body->{},()->{
   var n=get(actor,id);IdentityCommands.match(n.version(),etag);if(n.readAt()==null)db.update("UPDATE notification SET read_at=CURRENT_TIMESTAMP,lock_version=lock_version+1 WHERE id=?",id);
   return outcome(get(actor,id),id,n.readAt()==null?n.version()+1:n.version(),n.orderId(),n.jobId());
  });
 }
 private AccessPolicy.Context manager(SessionUser actor) {commands.require(actor,"user.manage");var ctx=policy.context(actor,null);if(ctx.supplierUser())throw new ApiProblem(403,"FORBIDDEN");return ctx;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public IdentityService.Page<Delivery> deliveries(SessionUser actor,int page,int size) {
  var ctx=manager(actor);var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);
  return new IdentityService.Page<>(sql.query("SELECT d.* FROM notification_delivery d WHERE "+visible(ctx)+" ORDER BY d.created_at DESC,d.id LIMIT :limit OFFSET :offset",args,DELIVERY),sql.queryForObject("SELECT count(*) FROM notification_delivery d WHERE "+visible(ctx),args,Long.class),page,size);
 }
 private Delivery delivery(SessionUser actor,UUID id) {
  var ctx=manager(actor);var rows=sql.query("SELECT d.* FROM notification_delivery d WHERE d.id=:id AND "+visible(ctx),policy.parameters(ctx).addValue("id",id),DELIVERY);if(rows.isEmpty())throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
 }
 public IdentityService.StoredResult retry(SessionUser actor,UUID id,String key,String etag) {
  return commands.execute(actor,"user.manage","notification.retry:"+id,key,etag,()->delivery(actor,id),body->{},()->{
   var d=delivery(actor,id);IdentityCommands.match(d.version(),etag);if(!d.status().equals("FAILED"))throw new ApiProblem(409,"RETRY_NOT_ALLOWED");
   db.update("UPDATE notification_delivery SET status='PENDING',attempts=0,error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP,lock_version=lock_version+1 WHERE id=?",id);
   return outcome(delivery(actor,id),id,d.version()+1,d.orderId(),d.jobId());
  });
 }
 private IdentityCommands.Outcome outcome(Object body,UUID id,long version,UUID order,UUID job) {
  var scope=order!=null?db.queryForMap("SELECT company_id,supplier_id FROM purchase_order WHERE id=?",order):db.queryForMap("SELECT c.company_id,c.supplier_id FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE j.id=?",job);
  return new IdentityCommands.Outcome(body,id,version,"NOTIFICATION",(UUID)scope.get("supplier_id"),List.of((UUID)scope.get("company_id")));
 }
 private void seed() {
  // The business outbox remains immutable. A unique origin makes this scan replayable.
  db.update("INSERT INTO notification_delivery(id,deployment_id,origin_kind,origin_id,origin_version,event_type,order_id,source_revision) SELECT gen_random_uuid(),e.deployment_id,'COLLABORATION',e.id,e.aggregate_version,e.event_type,e.order_id,e.source_revision FROM outbox_event e WHERE NOT EXISTS(SELECT 1 FROM notification_delivery d WHERE d.origin_kind='COLLABORATION' AND d.origin_id=e.id AND d.origin_version=e.aggregate_version) ORDER BY e.created_at,e.id LIMIT 100 ON CONFLICT DO NOTHING");
 }
 private boolean current(Map<String,Object> d) {
  UUID origin=(UUID)d.get("origin_id"),order=(UUID)d.get("order_id");long version=((Number)d.get("origin_version")).longValue();String kind=(String)d.get("origin_kind");
  if(kind.equals("SYNC"))return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sync_job WHERE id=? AND status='FAILED' AND lock_version=?)",Boolean.class,origin,version));
  if(kind.equals("ORDER"))return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_order WHERE id=? AND consistency_hold AND lock_version=?)",Boolean.class,order,version));
  var rows=db.queryForList("SELECT e.*,o.source_revision current_source FROM outbox_event e JOIN purchase_order o ON o.id=e.order_id WHERE e.id=?",origin);if(rows.isEmpty())return false;var e=rows.getFirst();
  if(!e.get("status").equals("PENDING")||!e.get("source_revision").equals(e.get("current_source")))return false;
  String type=(String)e.get("event_type");UUID aggregate=(UUID)e.get("aggregate_id");String table=type.startsWith("confirmation.")?"confirmation":type.startsWith("delivery-change.")?"delivery_change":type.startsWith("delivery-plan.")?"delivery_plan":null;
  if(table!=null) {
   var refs=db.queryForList("SELECT x.lock_version,i.plant_id FROM "+table+" x JOIN "+(table.equals("delivery_change")?"confirmation cf ON cf.id=x.confirmation_id JOIN po_schedule s ON s.id=cf.schedule_id":"po_schedule s ON s.id=x.schedule_id")+" JOIN po_item i ON i.id=s.item_id WHERE x.id=? AND x.order_id=?",aggregate,order);
   if(refs.isEmpty()||((Number)refs.getFirst().get("lock_version")).longValue()!=version)return false;
   db.update("UPDATE notification_delivery SET plant_id=? WHERE id=?",refs.getFirst().get("plant_id"),d.get("id"));return true;
  }
  if(type.startsWith("asn.")) {
   var refs=db.queryForList("SELECT h.lock_version,i.plant_id FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN po_schedule s ON s.id=l.schedule_id JOIN po_item i ON i.id=s.item_id WHERE l.id=? AND l.order_id=?",aggregate,order);
   if(refs.isEmpty()||((Number)refs.getFirst().get("lock_version")).longValue()!=version)return false;
   db.update("UPDATE notification_delivery SET plant_id=? WHERE id=?",refs.getFirst().get("plant_id"),d.get("id"));return true;
  }
  return type.equals("order.collaboration.superseded")&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_order WHERE id=? AND lock_version=?)",Boolean.class,order,version));
 }
 public void tick() {
  tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class);seed();});
  var ids=db.queryForList("SELECT id FROM notification_delivery WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 20",UUID.class);
  for(UUID id:ids)try {tx.executeWithoutResult(status->dispatch(id));}catch(Exception failed) {
   tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class);db.update("UPDATE notification_delivery SET attempts=attempts+1,status=CASE WHEN attempts+1>=5 THEN 'FAILED' ELSE 'PENDING' END,error_code='NOTIFICATION_DISPATCH_FAILED',next_attempt_at=CURRENT_TIMESTAMP+make_interval(secs=>LEAST(300,CAST(power(2,attempts+1) AS integer))),lock_version=lock_version+1 WHERE id=? AND status='PENDING'",id);});
  }
 }
 private void dispatch(UUID id) {
  db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class);
  var d=db.queryForMap("SELECT * FROM notification_delivery WHERE id=?",id);if(!d.get("status").equals("PENDING")||!Boolean.TRUE.equals(db.queryForObject("SELECT next_attempt_at<=CURRENT_TIMESTAMP FROM notification_delivery WHERE id=?",Boolean.class,id)))return;
  if(!current(d)) {db.update("UPDATE notification_delivery SET status='SUPERSEDED',lock_version=lock_version+1 WHERE id=?",id);return;}
  var users=db.query("SELECT id,login,session_version FROM app_user WHERE status='ACTIVE' AND deployment_id="+AccessPolicy.REALM,(r,n)->new SessionUser(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)));
  for(var user:users) {
   AccessPolicy.Context ctx;try {ctx=policy.context(user,null);}catch(ApiProblem invalid){continue;}
   if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_delivery d WHERE d.id=:id AND "+visible(ctx)+")",policy.parameters(ctx).addValue("id",id),Boolean.class)))
    db.update("INSERT INTO notification(id,deployment_id,delivery_id,recipient_id) SELECT gen_random_uuid(),deployment_id,?,? FROM app_guard WHERE id=1 ON CONFLICT DO NOTHING",id,user.id());
  }
  db.update("UPDATE notification_delivery SET status='DELIVERED',attempts=attempts+1,error_code=NULL,delivered_at=CURRENT_TIMESTAMP,lock_version=lock_version+1 WHERE id=?",id);
 }
}
