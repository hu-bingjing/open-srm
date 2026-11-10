package org.opensrm.shipping;
import java.util.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;
import org.opensrm.integration.port.ErpData.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Invoked only by the authorized sync worker inside its locked page transaction. */
@Component
public class ReceiptImporter {
 private final JdbcTemplate db;private final ObjectMapper json;private final ShippingService shipping;
 public ReceiptImporter(JdbcTemplate db,ObjectMapper json,ShippingService shipping) {this.db=db;this.json=json;this.shipping=shipping;}
 private BigDecimal sum(String query,Object...args) {return db.queryForObject(query,BigDecimal.class,args);}
 private int issue(UUID connection,UUID job,GoodsReceipt v,String hash,String code,UUID order) {
  UUID issueId=UUID.randomUUID();int inserted=db.update("INSERT INTO sync_issue(id,connection_id,job_id,external_key,source_revision,content_hash,error_code) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",issueId,connection,job,v.source().externalKey(),v.source().sourceRevision(),hash,code);
  if(inserted>0) {
   var context=db.queryForMap("SELECT j.actor_id,j.request_id,c.company_id FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE j.id=?",job);UUID audit=UUID.randomUUID();
   db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id,category) SELECT ?,deployment_id,?,'receipt.quarantine',?,?,'SYNC' FROM app_guard WHERE id=1",audit,context.get("actor_id"),issueId,context.get("request_id"));
   db.update("INSERT INTO audit_event_company(deployment_id,event_id,company_id) SELECT deployment_id,?,? FROM app_guard WHERE id=1",audit,context.get("company_id"));
  }
  if(order!=null) {db.update("UPDATE purchase_order SET consistency_hold=true,lock_version=lock_version+1 WHERE id=? AND NOT consistency_hold",order);db.update("INSERT INTO notification_delivery(id,deployment_id,origin_kind,origin_id,origin_version,event_type,order_id) SELECT gen_random_uuid(),deployment_id,'ORDER',id,lock_version,'order.conflict',id FROM purchase_order WHERE id=? AND consistency_hold ON CONFLICT DO NOTHING",order);}
  return 2;
 }
 public int apply(UUID connection,UUID job,GoodsReceipt v) {
  String payload=json.writeValueAsString(v),hash;
  try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));} catch(Exception e) {throw new IllegalStateException(e);}
  String key=v.source().externalKey();var existing=db.queryForList("SELECT i.content_hash FROM receipt_event e JOIN receipt_inbox i ON i.id=e.id WHERE e.connection_id=? AND e.external_key=?",String.class,connection,key);
  var orders=db.queryForList("SELECT id FROM purchase_order WHERE connection_id=? AND external_id=?",UUID.class,connection,v.orderKey());UUID order=orders.isEmpty()?null:orders.getFirst();
  UUID inbox=UUID.randomUUID();db.update("INSERT INTO receipt_inbox(id,connection_id,external_key,content_hash,payload) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",inbox,connection,key,hash,payload);
  if(!existing.isEmpty()) return existing.getFirst().equals(hash)?1:issue(connection,job,v,hash,"RECEIPT_SOURCE_CONFLICT",order);
  // A previously quarantined identical event can be retried after prerequisites arrive.
  inbox=db.queryForObject("SELECT id FROM receipt_inbox WHERE connection_id=? AND external_key=? AND content_hash=?",UUID.class,connection,key,hash);
  var config=db.queryForMap("SELECT * FROM erp_connection WHERE id=?",connection);
  if(!v.source().sourceSystem().equals(config.get("source_system")) || !v.source().sourceClient().equals(config.get("source_client"))) return issue(connection,job,v,hash,"INVALID_SOURCE",order);
  if(order==null) return issue(connection,job,v,hash,"RECEIPT_ORDER_UNKNOWN",null);
  String scheduleKey=v.scheduleKey();
  if(scheduleKey==null||scheduleKey.isBlank()) {
   List<String> candidates;
   if(v.eventType().equals("REVERSAL")) {
    candidates=db.queryForList("SELECT s.external_schedule FROM receipt_event e JOIN po_schedule s ON s.id=e.schedule_id JOIN po_item i ON i.id=s.item_id WHERE e.connection_id=? AND e.external_key=? AND e.event_type='POSTED' AND e.order_id=? AND i.external_item=?",String.class,connection,v.originalKey(),order,v.itemKey());
    if(candidates.isEmpty())return issue(connection,job,v,hash,"REVERSAL_ORIGINAL_MISSING",order);
   } else candidates=db.queryForList("SELECT s.external_schedule FROM po_schedule s JOIN po_item i ON i.id=s.item_id JOIN purchase_order o ON o.current_revision_id=i.revision_id WHERE o.id=? AND i.external_item=?",String.class,order,v.itemKey());
   if(candidates.size()!=1)return issue(connection,job,v,hash,"RECEIPT_SCHEDULE_AMBIGUOUS",order);
   scheduleKey=candidates.getFirst();
  }
  var refs=db.queryForList("SELECT s.id,s.ordered_quantity,i.unit FROM po_schedule s JOIN po_item i ON i.id=s.item_id JOIN purchase_order o ON o.current_revision_id=i.revision_id WHERE o.id=? AND i.external_item=? AND s.external_schedule=?",order,v.itemKey(),scheduleKey);
  // A removed line still needs its authoritative history; map the latest known immutable snapshot.
  if(refs.isEmpty()) refs=db.queryForList("SELECT s.id,s.ordered_quantity,i.unit FROM po_schedule s JOIN po_item i ON i.id=s.item_id JOIN po_revision r ON r.id=i.revision_id WHERE r.order_id=? AND i.external_item=? AND s.external_schedule=? ORDER BY r.source_sequence DESC LIMIT 1",order,v.itemKey(),scheduleKey);
  if(refs.isEmpty()) return issue(connection,job,v,hash,"RECEIPT_LINE_UNKNOWN",order);
  var ref=refs.getFirst();UUID schedule=(UUID)ref.get("id");
  try {ShippingQuantities.positive(v.quantity());} catch(RuntimeException invalid) {return issue(connection,job,v,hash,"RECEIPT_QUANTITY_INVALID",order);}
  if(!v.unit().equals(ref.get("unit")) || !Set.of("POSTED","REVERSAL").contains(v.eventType())) return issue(connection,job,v,hash,"RECEIPT_MAPPING_INVALID",order);
  UUID original=null;BigDecimal originalUnallocated=null;
  if(v.eventType().equals("REVERSAL")) {
   var originals=db.queryForList("SELECT e.* FROM receipt_event e JOIN po_schedule s ON s.id=e.schedule_id JOIN po_item i ON i.id=s.item_id WHERE e.connection_id=? AND e.external_key=? AND e.event_type='POSTED' AND e.order_id=? AND i.external_item=? AND s.external_schedule=?",connection,v.originalKey(),order,v.itemKey(),scheduleKey);
   if(originals.isEmpty()) return issue(connection,job,v,hash,"REVERSAL_ORIGINAL_MISSING",order);var old=originals.getFirst();original=(UUID)old.get("id");
   BigDecimal reversed=sum("SELECT COALESCE(sum(quantity),0) FROM receipt_event WHERE original_id=?",original);
   if(reversed.add(v.quantity()).compareTo((BigDecimal)old.get("quantity"))>0) return issue(connection,job,v,hash,"REVERSAL_QUANTITY_EXCEEDED",order);
   originalUnallocated=((BigDecimal)old.get("quantity")).subtract(sum("SELECT COALESCE(sum(quantity),0) FROM receipt_allocation WHERE event_id=?",original)).subtract(reversed).add(sum("SELECT COALESCE(sum(a.quantity),0) FROM receipt_allocation a JOIN receipt_event e ON e.id=a.event_id WHERE e.original_id=?",original));
  } else if(v.originalKey()!=null) return issue(connection,job,v,hash,"RECEIPT_MAPPING_INVALID",order);
  var allocations=new LinkedHashMap<UUID,BigDecimal>();BigDecimal total=BigDecimal.ZERO;
  for(var a:v.allocations()) {
   var lines=db.queryForList("SELECT l.* FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN po_schedule s ON s.id=l.schedule_id JOIN po_item i ON i.id=s.item_id WHERE h.reference=? AND h.company_id=? AND h.supplier_id=? AND h.status='SUBMITTED' AND l.line_number=? AND l.order_id=? AND i.external_item=? AND s.external_schedule=?",a.asnReference(),config.get("company_id"),config.get("supplier_id"),a.lineNumber(),order,v.itemKey(),scheduleKey);
   if(lines.isEmpty()) return issue(connection,job,v,hash,"RECEIPT_ASN_MAPPING_INVALID",order);
   UUID line=(UUID)lines.getFirst().get("id");try {ShippingQuantities.positive(a.quantity());} catch(RuntimeException invalid) {return issue(connection,job,v,hash,"RECEIPT_ALLOCATION_INVALID",order);}
   if(allocations.putIfAbsent(line,a.quantity())!=null) return issue(connection,job,v,hash,"RECEIPT_ALLOCATION_DUPLICATE",order);
   BigDecimal available;
   if(original==null) available=((BigDecimal)lines.getFirst().get("quantity")).subtract(shipping.allocated(line));
   else available=sum("SELECT COALESCE(sum(quantity),0) FROM receipt_allocation WHERE event_id=? AND asn_line_id=?",original,line).subtract(sum("SELECT COALESCE(sum(a.quantity),0) FROM receipt_allocation a JOIN receipt_event e ON e.id=a.event_id WHERE e.original_id=? AND a.asn_line_id=?",original,line));
   if(a.quantity().compareTo(available)>0) return issue(connection,job,v,hash,"RECEIPT_ALLOCATION_EXCEEDED",order);total=total.add(a.quantity());
  }
  if(total.compareTo(v.quantity())>0 || (original!=null && v.quantity().subtract(total).compareTo(originalUnallocated)>0)) return issue(connection,job,v,hash,"RECEIPT_ALLOCATION_INVALID",order);
  db.update("INSERT INTO receipt_event(id,deployment_id,connection_id,external_key,order_id,schedule_id,event_type,original_id,quantity,posting_date) SELECT ?,deployment_id,?,?,?,?,?,?,?,? FROM app_guard WHERE id=1",inbox,connection,key,order,schedule,v.eventType(),original,v.quantity(),v.postingDate());
  for(var a:allocations.entrySet()) db.update("INSERT INTO receipt_allocation(event_id,asn_line_id,quantity) VALUES (?,?,?)",inbox,a.getKey(),a.getValue());
  db.update("UPDATE purchase_order SET lock_version=lock_version+1 WHERE id=?",order);
  var jobRow=db.queryForMap("SELECT actor_id,request_id FROM sync_job WHERE id=?",job);UUID audit=UUID.randomUUID();
  db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id,category,supplier_id) SELECT ?,deployment_id,?,'receipt.import',?,?,'COLLABORATION',? FROM app_guard WHERE id=1",audit,jobRow.get("actor_id"),inbox,jobRow.get("request_id"),config.get("supplier_id"));
  db.update("INSERT INTO audit_event_company(deployment_id,event_id,company_id) SELECT deployment_id,?,? FROM app_guard WHERE id=1",audit,config.get("company_id"));
  BigDecimal net=shipping.received(order,v.itemKey(),scheduleKey);
  if(net.add(shipping.transit(order,v.itemKey(),scheduleKey)).compareTo((BigDecimal)ref.get("ordered_quantity"))>0) return issue(connection,job,v,hash,"ERP_OVER_RECEIPT",order);
  return 0;
 }
}
