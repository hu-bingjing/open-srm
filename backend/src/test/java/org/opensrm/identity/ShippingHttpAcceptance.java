package org.opensrm.identity;
import java.util.*;
import java.net.http.*;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
abstract class ShippingHttpAcceptance extends CollaborationHttpAcceptance {
 String firstSubmitKey,firstSubmitTag;
 String shippingConnection,shippingOrder,shippingSchedule,shippingPlan,shippingUser,asnA,asnB;
 final HttpClient ship=client();
 String asnTag(String id) throws Exception {return "\""+get(ship,"/asns/"+id).get("version").asLong()+"\"";}
 Map<String,Object> draft(String ref,String qty) throws Exception {var o=get(ship,"/purchase-orders/"+shippingOrder);return Map.of("reference",ref,"lines",List.of(Map.of("planId",shippingPlan,"quantity",qty,"sourceRevision",o.get("sourceRevision").asString(),"orderVersion",o.get("version").asLong())));}
 String createAsn(String ref,String qty) throws Exception {return id(ok(call(ship,"POST","/asns",draft(ref,qty)),201));}
 HttpResponse<String> submitAsn(String id) throws Exception {return send(ship,"POST","/asns/"+id+"/submit",null,true,UUID.randomUUID().toString(),asnTag(id));}
 JsonNode receiptSync(String scenario) throws Exception {return waitJob(id(ok(call(admin,"POST","/sync-jobs",Map.of("connectionId",shippingConnection,"entityType","GOODS_RECEIPT","scenario",scenario)),202)));}
 JsonNode balance() throws Exception {return get(ship,"/purchase-orders/"+shippingOrder+"/shipping-balances").get(0);}
 @Test @Order(80) void shippingRequiresExplicitActionScopeAndCreatesRealDraft() throws Exception {
  shippingConnection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Shipping Mock","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));sync(shippingConnection,"CONFIRMATION");shippingOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(shippingConnection));shippingSchedule=schedule(shippingOrder,0,0);confirm(shippingOrder,shippingSchedule,"100",date(shippingOrder,0,0,0));shippingPlan=id(get(supplierClient,"/delivery-plans?orderId="+shippingOrder).get("items").get(0));
  shippingUser=createActor("shipping-supplier","SUPPLIER",ship);assertEquals(404,call(ship,"POST","/asns",draft("ASN-A","10")).statusCode());grant(shippingUser,"asn.manage",companyA,null,null);asnA=createAsn("ASN-A","10");ok(send(ship,"PUT","/asns/"+asnA,draft("ASN-A","10"),true,UUID.randomUUID().toString(),asnTag(asnA)),200);assertEquals("0",balance().get("submittedQuantity").asString());assertEquals(0,count("receipt_event"));
 }
 @Test @Order(81) void submittedAsnOnlyReservesAndReplayDoesNotDoubleShip() throws Exception {
  String key=UUID.randomUUID().toString(),tag=asnTag(asnA);firstSubmitKey=key;firstSubmitTag=tag;var first=ok(send(ship,"POST","/asns/"+asnA+"/submit",null,true,key,tag),200);assertEquals(first,ok(send(ship,"POST","/asns/"+asnA+"/submit",null,true,key,tag),200));assertEquals("10.000000",balance().get("inTransitQuantity").asString());assertEquals("0",balance().get("receivedQuantity").asString());
  asnB=createAsn("ASN-B","20");ok(submitAsn(asnB),200);assertEquals("70.000000",balance().get("availableQuantity").asString());
 }
 @Test @Order(82) void staleAndOverQuantityCannotChangeAuditOrLedger() throws Exception {
  String id=createAsn("ASN-OVER","71");long events=count("audit_event"),receipts=count("receipt_event");assertEquals(409,submitAsn(id).statusCode());assertEquals(events,count("audit_event"));assertEquals(receipts,count("receipt_event"));assertEquals("DRAFT",get(ship,"/asns/"+id).get("status").asString());
  assertEquals(412,send(ship,"POST","/asns/"+id+"/submit",null,true,UUID.randomUUID().toString(),"\"9999\"").statusCode());
  var body=draft("ASN-DUP","1");var line=((List<?>)body.get("lines")).get(0);assertEquals(422,call(ship,"POST","/asns",Map.of("reference","ASN-DUP","lines",List.of(line,line))).statusCode());
 }
 @Test @Order(83) void oneReceiptAllocatesTwoAsnsAndNoAsnReceiptsStillCount() throws Exception {
  var job=receiptSync("GR_INITIAL");assertEquals("SUCCEEDED",job.get("status").asString());assertEquals(3,job.get("applied").asInt());assertEquals("30.000000",balance().get("receivedQuantity").asString());assertEquals("10.000000",balance().get("inTransitQuantity").asString());assertEquals("60.000000",balance().get("availableQuantity").asString());assertEquals(2,count("receipt_allocation"));
  var repeat=receiptSync("GR_INITIAL");assertEquals(3,repeat.get("skipped").asInt());assertEquals(3,count("receipt_event"));
 }
 @Test @Order(84) void receiptsPreventAsnCancellationAndShippedCommitmentReschedule() throws Exception {
  assertEquals(409,send(ship,"POST","/asns/"+asnA+"/cancel",null,true,UUID.randomUUID().toString(),asnTag(asnA)).statusCode());
  var c=get(supplierClient,"/confirmations?orderId="+shippingOrder).get("items").get(0);assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+shippingOrder+"/delivery-changes",Map.of("confirmationId",id(c),"sourceRevision","mock-r1","proposedDate","2027-05-01","reason","Virtual reschedule"),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+shippingOrder)).statusCode());
 }
 @Test @Order(85) void partialAndFullReversalsRestoreTransitWithoutRestoringPlanShipmentAllowance() throws Exception {
  assertEquals("SUCCEEDED",receiptSync("GR_PARTIAL_REVERSE").get("status").asString());assertEquals("25.000000",balance().get("receivedQuantity").asString());assertEquals("15.000000",balance().get("inTransitQuantity").asString());
  assertEquals("SUCCEEDED",receiptSync("GR_FULL_REVERSE").get("status").asString());assertEquals("10.000000",balance().get("receivedQuantity").asString());assertEquals("30.000000",balance().get("inTransitQuantity").asString());assertEquals("60.000000",balance().get("availableQuantity").asString());assertEquals(5,count("receipt_event"));
 }
 @Test @Order(86) void cancellationBeforeAnyReceiptReleasesReservation() throws Exception {
  String id=createAsn("ASN-CANCEL","10");ok(submitAsn(id),200);ok(send(ship,"POST","/asns/"+id+"/cancel",null,true,UUID.randomUUID().toString(),asnTag(id)),200);assertEquals("60.000000",balance().get("availableQuantity").asString());
 }
 @Test @Order(87) void concurrentDraftSubmissionsAreSerializedAndNeverOvership() throws Exception {
  String a=createAsn("ASN-RACE-A","50"),b=createAsn("ASN-RACE-B","50");String ta=asnTag(a),tb=asnTag(b);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try {var x=pool.submit(()->send(ship,"POST","/asns/"+a+"/submit",null,true,UUID.randomUUID().toString(),ta).statusCode());var y=pool.submit(()->send(ship,"POST","/asns/"+b+"/submit",null,true,UUID.randomUUID().toString(),tb).statusCode());var codes=new ArrayList<>(List.of(x.get(),y.get()));Collections.sort(codes);assertEquals(List.of(200,412),codes);}finally{pool.shutdownNow();}assertEquals("10.000000",balance().get("availableQuantity").asString());
 }
 @Test @Order(88) void crossSupplierAndRevokedScopeCannotReadOrReplay() throws Exception {
  assertEquals(404,call(external,"GET","/asns/"+asnA,null).statusCode());assertEquals(0,get(external,"/asns").get("total").asInt());assertEquals(0,get(external,"/goods-receipts").get("total").asInt());assertEquals(405,call(ship,"POST","/goods-receipts",Map.of()).statusCode());
  db.update("UPDATE organization_grant SET active=false WHERE user_id=? AND permission='asn.manage'",UUID.fromString(shippingUser));assertEquals(404,send(ship,"POST","/asns/"+asnA+"/submit",null,true,firstSubmitKey,firstSubmitTag).statusCode());db.update("UPDATE organization_grant SET active=true WHERE user_id=? AND permission='asn.manage'",UUID.fromString(shippingUser));
 }
 @Test @Order(89) void newVersionPreservesShipmentsAndReceiptsAndFreezesNewCollaboration() throws Exception {
  sync(shippingConnection,"UPDATE");assertTrue(get(ship,"/purchase-orders/"+shippingOrder).get("consistencyHold").asBoolean());assertEquals("SUBMITTED",get(ship,"/asns/"+asnA).get("status").asString());assertEquals(5,count("receipt_event"));assertEquals("10.000000",balance().get("receivedQuantity").asString());assertEquals("0",balance().get("availableQuantity").asString());assertEquals(409,submitAsn(asnA).statusCode());
 }
 @Test @Order(90) void sourceConflictRetainsImmutableReceiptAndQuarantinesPayload() throws Exception {
  assertEquals("QUARANTINED",receiptSync("GR_CONFLICT").get("status").asString());assertEquals(5,count("receipt_event"));assertTrue(count("receipt_inbox")>count("receipt_event"));
 }
 @Test @Order(91) void unknownReceiptAndAuthoritativeOverReceiptRemainVisibleWithoutClamping() throws Exception {
  assertEquals("QUARANTINED",receiptSync("GR_UNMATCHED").get("status").asString());assertEquals("QUARANTINED",receiptSync("GR_OVER").get("status").asString());assertEquals("210.000000",balance().get("receivedQuantity").asString());assertEquals("0",balance().get("availableQuantity").asString());
 }
 @Test @Order(92) void receiptFailureResumesOriginalCheckpointWithoutDuplicatingEvents() throws Exception {
  var job=receiptSync("GR_FAIL_ONCE");assertEquals("FAILED",job.get("status").asString());assertEquals(2,job.get("processed").asInt());ok(send(admin,"POST","/sync-jobs/"+id(job)+"/retry",null,true,UUID.randomUUID().toString(),"\""+job.get("version").asLong()+"\""),202);var done=waitJob(id(job));assertEquals("SUCCEEDED",done.get("status").asString());assertEquals(3,done.get("skipped").asInt());assertEquals(6,count("receipt_event"));
 }

 @Test @Order(93) void multiOrderMultiPlantAsnIsAtomicAndEveryLineMustBeAuthorized() throws Exception {
  String connection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Multi Plant Shipping","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));sync(connection,"CONFIRMATION");grant(shippingUser,"confirmation.submit",companyA,null,null);
  var inputLines=new ArrayList<Map<String,Object>>();
  for(String number:List.of("PO-1001","PO-1002")) {
   String order=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id=?",String.class,UUID.fromString(connection),number);var o=get(ship,"/purchase-orders/"+order);var schedule=o.get("items").get(0).get("schedules").get(0);
   ok(send(ship,"POST","/purchase-orders/"+order+"/confirmations",Map.of("scheduleId",id(schedule),"sourceRevision","mock-r1","quantity","20","promisedDate",schedule.get("requestedDate").asString()),true,UUID.randomUUID().toString(),etag(ship,"/purchase-orders/"+order)),201);
   String plan=id(get(ship,"/delivery-plans?orderId="+order).get("items").get(0));inputLines.add(Map.of("planId",plan,"sourceRevision","mock-r1","quantity","10","orderVersion",get(ship,"/purchase-orders/"+order).get("version").asLong()));
  }
  String id=id(ok(call(ship,"POST","/asns",Map.of("reference","ASN-MULTI","lines",inputLines)),201));
  db.update("UPDATE organization_grant SET plant_id=? WHERE user_id=? AND permission='asn.manage'",UUID.fromString(plantA),UUID.fromString(shippingUser));long audit=count("audit_event");assertEquals(404,submitAsn(id).statusCode());assertEquals(audit,count("audit_event"));assertEquals("DRAFT",get(ship,"/asns/"+id).get("status").asString());
  db.update("UPDATE organization_grant SET plant_id=NULL WHERE user_id=? AND permission='asn.manage'",UUID.fromString(shippingUser));ok(submitAsn(id),200);assertEquals(2,get(ship,"/asns/"+id).get("lines").size());
  db.update("UPDATE organization_grant SET plant_id=? WHERE user_id=? AND permission='order.read'",UUID.fromString(plantA),UUID.fromString(shippingUser));assertEquals(404,call(ship,"GET","/asns/"+id,null).statusCode());for(var h:get(ship,"/asns?size=100").get("items")) assertNotEquals(id,id(h));db.update("UPDATE organization_grant SET plant_id=NULL WHERE user_id=? AND permission='order.read'",UUID.fromString(shippingUser));
 }
 @Test @Order(94) void reversalBeforeOriginalIsQuarantinedWithoutLosingPayload() throws Exception {
  String old=shippingConnection;shippingConnection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Reversal Before Receipt","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA))),201));sync(shippingConnection,"CONFIRMATION");
  var first=receiptSync("GR_PARTIAL_REVERSE");assertEquals("QUARANTINED",first.get("status").asString());assertEquals("REVERSAL_ORIGINAL_MISSING",get(admin,"/sync-jobs/"+id(first)+"/issues").get("items").get(0).get("errorCode").asString());shippingConnection=old;
 }

 @Test @Order(95) void unboundSupplierSeesNoAsnHeadersOrCounts() throws Exception {
  var user=client();String id=createActor("unbound-shipping","SUPPLIER",user);db.update("DELETE FROM user_supplier WHERE user_id=?",UUID.fromString(id));assertEquals(0,get(user,"/asns").get("total").asInt());assertEquals(404,call(user,"GET","/asns/"+asnA,null).statusCode());
 }
}
