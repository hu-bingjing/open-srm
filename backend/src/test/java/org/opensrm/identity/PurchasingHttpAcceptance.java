package org.opensrm.identity;
import java.util.*;
import java.net.*;
import java.net.http.*;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class PurchasingHttpAcceptance extends OrganizationHttpAcceptance {
 @org.springframework.beans.factory.annotation.Autowired org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduledTasks;
 @AfterAll void stopWorkerBeforeTestDatabase() { scheduledTasks.destroy(); }
 String connectionA,connectionB,orderA,orderB,readGrant;
 JsonNode waitJob(String id) throws Exception {long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end) {var j=get(admin,"/sync-jobs/"+id);if(Set.of("SUCCEEDED","FAILED","QUARANTINED").contains(j.get("status").asString())) return j;Thread.sleep(100);}fail("Sync job did not reach terminal state");return null;}
 JsonNode sync(String connection,String scenario) throws Exception {var j=ok(call(admin,"POST","/sync-jobs",Map.of("connectionId",connection,"entityType","PURCHASE_ORDER","scenario",scenario)),202);return waitJob(id(j));}
 @Test @Order(40) void explicitConnectionScopeIsRequired() throws Exception {
  var body=Map.of("name","Mock Company A","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2));
  assertEquals(404,call(admin,"POST","/erp-connections",body).statusCode());
  grant(adminId,"sync.manage",companyA,null,null);grant(adminId,"sync.manage",companyB,null,null);
  connectionA=id(ok(call(admin,"POST","/erp-connections",body),201));
  connectionB=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Mock Company B","companyId",companyB,"purchasingOrgId",orgA,"supplierId",supplierB,"plantIds",List.of(plantB))),201));
  assertEquals(2,get(admin,"/erp-connections").get("total").asInt());
  assertEquals(403,call(buyer,"GET","/sync-jobs",null).statusCode());
  assertEquals(0,get(admin,"/purchase-orders").get("total").asInt());
 }
 @Test @Order(41) void mockImportsRealHeadersItemsSchedulesAndMaterials() throws Exception {
  var a=sync(connectionA,"INITIAL");var b=sync(connectionB,"INITIAL");assertEquals("SUCCEEDED",a.get("status").asString());assertEquals(4,a.get("applied").asInt());assertEquals(4,b.get("applied").asInt());
  assertEquals(0,get(admin,"/purchase-orders").get("total").asInt());
  grant(adminId,"order.read",companyA,null,null);grant(adminId,"order.read",companyB,null,null);
  var orders=get(admin,"/purchase-orders?companyId="+companyA);assertEquals(4,orders.get("total").asInt());
  orderA=id(get(admin,"/purchase-orders?companyId="+companyA+"&query=PO-1001").get("items").get(0));
  orderB=id(get(admin,"/purchase-orders?companyId="+companyB+"&query=PO-1001").get("items").get(0));
  var o=get(admin,"/purchase-orders/"+orderA);assertEquals(2,o.get("items").size());assertEquals(2,o.get("items").get(0).get("schedules").size());assertEquals("60.000000",o.get("items").get(0).get("schedules").get(0).get("orderedQuantity").asString());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM material",Integer.class));
  assertEquals(405,call(admin,"POST","/purchase-orders",Map.of()).statusCode());
 }
 @Test @Order(42) void tupleFiltersCurrentAndHistoricalLinesBeforePagination() throws Exception {
  grant(buyerId,"order.read",companyA,plantA,orgA);grant(buyerId,"order.read",companyA,plantA2,orgA2);
  var items=get(buyer,"/purchase-orders?size=1");assertEquals(3,items.get("total").asInt());assertEquals(1,items.get("items").size());
  var o=get(buyer,"/purchase-orders/"+orderA);assertEquals(1,o.get("items").size());assertEquals(plantA,o.get("items").get(0).get("plantId").asString());
  assertEquals(404,call(buyer,"GET","/purchase-orders/"+orderB,null).statusCode());assertEquals(0,get(buyer,"/purchase-orders?companyId="+companyB).get("total").asInt());
  assertEquals(0,get(buyer,"/purchase-orders?plantId="+plantA2).get("total").asInt());
  assertEquals(1,get(buyer,"/purchase-orders/"+orderA+"/revisions").get("items").get(0).get("items").size());
 }
 @Test @Order(43) void duplicateAndIdempotentStartHaveNoDuplicateOrdersOrAudit() throws Exception {
  long count=db.queryForObject("SELECT count(*) FROM po_revision",Long.class),audits=db.queryForObject("SELECT count(*) FROM audit_event WHERE operation='order.import'",Long.class);
  var body=Map.of("connectionId",connectionA,"entityType","PURCHASE_ORDER","scenario","DUPLICATE");
  var first=send(admin,"POST","/sync-jobs",body,true,"sync-idempotent-0001",null);var second=send(admin,"POST","/sync-jobs",body,true,"sync-idempotent-0001",null);ok(first,202);assertEquals(first.body(),second.body());
  var j=waitJob(id(json.readTree(first.body())));assertEquals(8,j.get("skipped").asInt());assertEquals(0,j.get("applied").asInt());assertEquals(count,db.queryForObject("SELECT count(*) FROM po_revision",Long.class));assertEquals(audits,db.queryForObject("SELECT count(*) FROM audit_event WHERE operation='order.import'",Long.class));
 }
 @Test @Order(44) void newerRevisionPreservesSnapshotsAndOldEventsAreQuarantined() throws Exception {
  assertEquals(4,sync(connectionA,"UPDATE").get("applied").asInt());var o=get(buyer,"/purchase-orders/"+orderA);assertEquals("mock-r2",o.get("sourceRevision").asString());assertEquals("55.000000",o.get("items").get(0).get("schedules").get(0).get("orderedQuantity").asString());
  var revisions=get(buyer,"/purchase-orders/"+orderA+"/revisions");assertEquals(2,revisions.get("total").asInt());assertEquals("60.000000",revisions.get("items").get(1).get("items").get(0).get("schedules").get(0).get("orderedQuantity").asString());assertEquals(1,revisions.get("items").get(1).get("items").size());
  var old=sync(connectionA,"OUT_OF_ORDER");assertEquals("QUARANTINED",old.get("status").asString());assertEquals(4,old.get("quarantined").asInt());assertEquals("OUT_OF_ORDER_REVISION",get(admin,"/sync-jobs/"+id(old)+"/issues").get("items").get(0).get("errorCode").asString());
  assertEquals("mock-r2",get(admin,"/purchase-orders/"+orderA).get("sourceRevision").asString());
 }
 @Test @Order(45) void sameVersionConflictHoldsOrderWithoutOverwritingHistory() throws Exception {
  var j=sync(connectionB,"CONFLICT");assertEquals(1,j.get("quarantined").asInt());assertTrue(get(admin,"/purchase-orders/"+orderB).get("consistencyHold").asBoolean());assertEquals("60.000000",get(admin,"/purchase-orders/"+orderB).get("items").get(0).get("schedules").get(0).get("orderedQuantity").asString());
  assertEquals(1,get(admin,"/purchase-orders/"+orderB+"/revisions").get("total").asInt());
 }
 @Test @Order(46) void cancelledOrdersNeverSilentlyReopen() throws Exception {
  assertEquals(4,sync(connectionA,"CANCEL").get("applied").asInt());assertEquals("CANCELLED",get(buyer,"/purchase-orders/"+orderA).get("lifecycle").asString());
  var j=sync(connectionA,"REOPEN");assertEquals(4,j.get("quarantined").asInt());var o=get(admin,"/purchase-orders/"+orderA);assertEquals("CANCELLED",o.get("lifecycle").asString());assertTrue(o.get("consistencyHold").asBoolean());assertEquals(3,get(admin,"/purchase-orders/"+orderA+"/revisions").get("total").asInt());
 }
 @Test @Order(47) void checkpointAndFailedRetryContinueWithoutReapplyingFirstPage() throws Exception {
  String c=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Mock Retry","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));
  var failed=sync(c,"FAIL_ONCE");assertEquals("FAILED",failed.get("status").asString());assertEquals(2,failed.get("processed").asInt());assertNotNull(failed.get("nextCursor"));
  String path="/sync-jobs/"+id(failed);ok(send(admin,"POST",path+"/retry",null,true,"retry-page-0001",etag(admin,path)),202);
  var completed=waitJob(id(failed));assertEquals("SUCCEEDED",completed.get("status").asString());assertEquals(4,completed.get("processed").asInt());assertEquals(4,completed.get("applied").asInt());assertEquals(1,completed.get("attempts").asInt());
 }
 @Test @Order(48) void invalidMappingsAreIsolatedAndRemovedLinesRemainInHistory() throws Exception {
  String c=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Mock Removal","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));
  assertEquals(1,sync(c,"INVALID_MAPPING").get("quarantined").asInt());sync(c,"INITIAL");sync(c,"REMOVE_LINE");
  UUID id=db.queryForObject("SELECT id FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",UUID.class,UUID.fromString(c));
  assertEquals(1,get(admin,"/purchase-orders/"+id).get("items").size());assertEquals(2,get(admin,"/purchase-orders/"+id+"/revisions").get("items").get(1).get("items").size());
 }
 @Test @Order(49) void supplierBindingAndReadGrantBothApplyToOrders() throws Exception {
  grant(externalId,"order.read",companyB,null,null);
  ok(send(admin,"PUT","/users/"+externalId+"/supplier-binding",Map.of("supplierId",supplierB),true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)),200);
  ok(call(external,"POST","/auth/login",Map.of("login","supplier-a","password",externalPassword)),200);
  assertEquals(4,get(external,"/purchase-orders").get("total").asInt());assertEquals(404,call(external,"GET","/purchase-orders/"+orderA,null).statusCode());assertEquals(404,call(external,"GET","/purchase-orders/"+orderA+"/revisions",null).statusCode());
  assertEquals(403,call(external,"GET","/sync-jobs",null).statusCode());
 }
 @Test @Order(50) void revokedScopeDeniesCachedStartAndConnectionMetadata() throws Exception {
  var body=Map.of("connectionId",connectionB,"entityType","PURCHASE_ORDER","scenario","INITIAL");ok(send(admin,"POST","/sync-jobs",body,true,"revoked-sync-0001",null),202);
  for(var g:get(admin,"/users/"+adminId+"/grants")) if(g.get("companyId").asString().equals(companyB)&&g.get("permission").asString().equals("sync.manage")) ok(send(admin,"DELETE","/users/"+adminId+"/grants/"+id(g),null,true,UUID.randomUUID().toString(),etag(admin,"/users/"+adminId)),200);
  assertEquals(404,send(admin,"POST","/sync-jobs",body,true,"revoked-sync-0001",null).statusCode());assertEquals(404,call(admin,"GET","/erp-connections/"+connectionB,null).statusCode());
  String jobId=db.queryForObject("SELECT id::text FROM sync_job WHERE connection_id=? ORDER BY created_at DESC LIMIT 1",String.class,UUID.fromString(connectionB));
  long end=System.nanoTime()+8_000_000_000L;while(System.nanoTime()<end && !db.queryForObject("SELECT status FROM sync_job WHERE id=?",String.class,UUID.fromString(jobId)).equals("FAILED")) Thread.sleep(100);
  assertEquals("FAILED",db.queryForObject("SELECT status FROM sync_job WHERE id=?",String.class,UUID.fromString(jobId)));
  grant(adminId,"sync.manage",companyB,null,null);
 }
 @Test @Order(51) void anotherAdministratorHasNoImplicitConnectionAccess() throws Exception {
  String password=UUID.randomUUID().toString();String user=id(ok(call(admin,"POST","/users",Map.of("login","integration-b","displayName","Integration Operator B","password",password,"roles",List.of("ADMIN"))),201));grant(user,"sync.manage",companyA,null,null);
  var other=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();ok(call(other,"POST","/auth/login",Map.of("login","integration-b","password",password)),200);
  assertEquals(0,get(other,"/erp-connections").get("total").asInt());assertEquals(404,call(other,"GET","/erp-connections/"+connectionA,null).statusCode());assertEquals(0,get(other,"/sync-jobs").get("total").asInt());
 }
 @Test @Order(52) void concurrentStreamAndRetryLimitAreEnforced() throws Exception {
  String c=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Mock Retry Limit","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA))),201));
  var body=Map.of("connectionId",c,"entityType","PURCHASE_ORDER","scenario","PERMANENT_FAIL");var j=ok(call(admin,"POST","/sync-jobs",body),202);
  assertEquals(409,call(admin,"POST","/sync-jobs",body).statusCode());j=waitJob(id(j));
  for(int n=1;n<=5;n++) {String path="/sync-jobs/"+id(j);ok(send(admin,"POST",path+"/retry",null,true,UUID.randomUUID().toString(),etag(admin,path)),202);j=waitJob(id(j));assertEquals(n,j.get("attempts").asInt());assertEquals(2,j.get("processed").asInt());}
  assertEquals(409,send(admin,"POST","/sync-jobs/"+id(j)+"/retry",null,true,UUID.randomUUID().toString(),etag(admin,"/sync-jobs/"+id(j))).statusCode());
 }
 @Test @Order(53) void revokingOrderReadScopeImmediatelyRemovesHistoryAndCounts() throws Exception {
  for(var g:get(admin,"/users/"+buyerId+"/grants")) if(g.get("permission").asString().equals("order.read")) ok(send(admin,"DELETE","/users/"+buyerId+"/grants/"+id(g),null,true,UUID.randomUUID().toString(),etag(admin,"/users/"+buyerId)),200);
  assertEquals(200,call(buyer,"GET","/auth/me",null).statusCode());assertEquals(0,get(buyer,"/purchase-orders").get("total").asInt());assertEquals(404,call(buyer,"GET","/purchase-orders/"+orderA+"/revisions",null).statusCode());
 }
 @Test @Order(54) void closedOrderCanReopenOnlyWithNewSourceVersion() throws Exception {
  String c=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Mock Closed","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA))),201));
  sync(c,"INITIAL");sync(c,"CLOSE");sync(c,"REOPEN");UUID order=db.queryForObject("SELECT id FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",UUID.class,UUID.fromString(c));var value=get(admin,"/purchase-orders/"+order);assertEquals("ACTIVE",value.get("lifecycle").asString());assertEquals("mock-r4",value.get("sourceRevision").asString());assertEquals(3,get(admin,"/purchase-orders/"+order+"/revisions").get("total").asInt());
 }
}
