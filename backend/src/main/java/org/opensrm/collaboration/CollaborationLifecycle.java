package org.opensrm.collaboration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Called only inside the caller's guarded business/import transaction. No external dispatch. */
@Component
public class CollaborationLifecycle {
 private final JdbcTemplate db;
 public CollaborationLifecycle(JdbcTemplate db) {this.db=db;}
 public void event(UUID order,UUID aggregate,long version,String revision,String type) {
  db.update("INSERT INTO outbox_event(id,deployment_id,order_id,aggregate_id,aggregate_version,source_revision,event_type,status) SELECT ?,deployment_id,?,?,?,?,?,'PENDING' FROM app_guard WHERE id=1 ON CONFLICT DO NOTHING",UUID.randomUUID(),order,aggregate,version,revision,type);
 }
 public void freeze(UUID order) {db.update("UPDATE outbox_event SET status='SUPERSEDED' WHERE order_id=? AND status='PENDING'",order);db.update("INSERT INTO notification_delivery(id,deployment_id,origin_kind,origin_id,origin_version,event_type,order_id) SELECT gen_random_uuid(),deployment_id,'ORDER',id,lock_version,'order.conflict',id FROM purchase_order WHERE id=? AND consistency_hold ON CONFLICT DO NOTHING",order);}
 public void supersede(UUID order,UUID actor,UUID request,String revision) {
  db.update("UPDATE purchase_order SET consistency_hold=true WHERE id=? AND (EXISTS(SELECT 1 FROM asn_line l JOIN asn h ON h.id=l.asn_id WHERE l.order_id=? AND h.status='SUBMITTED') OR EXISTS(SELECT 1 FROM receipt_event WHERE order_id=?))",order,order,order);
  db.update("UPDATE asn SET status='SUPERSEDED',lock_version=lock_version+1 WHERE status='DRAFT' AND id IN (SELECT asn_id FROM asn_line WHERE order_id=?)",order);
  int changed=db.update("UPDATE confirmation SET status='SUPERSEDED',lock_version=lock_version+1 WHERE order_id=? AND status IN ('SUBMITTED','EFFECTIVE')",order);
  changed+=db.update("UPDATE delivery_change SET status='SUPERSEDED',lock_version=lock_version+1 WHERE order_id=? AND status='SUBMITTED'",order);
  changed+=db.update("UPDATE delivery_plan SET status='SUPERSEDED',lock_version=lock_version+1 WHERE order_id=? AND status='PLANNED'",order);
  freeze(order);
  if(changed>0) {
   UUID event=UUID.randomUUID();db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id,category,supplier_id) SELECT ?,deployment_id,?,'order.collaboration.supersede',id,?,'COLLABORATION',supplier_id FROM purchase_order WHERE id=?",event,actor,request,order);
   db.update("INSERT INTO audit_event_company(deployment_id,event_id,company_id) SELECT deployment_id,?,company_id FROM purchase_order WHERE id=?",event,order);
   event(order,order,db.queryForObject("SELECT lock_version FROM purchase_order WHERE id=?",Long.class,order)+1,revision,"order.collaboration.superseded");
  }
 }
}
