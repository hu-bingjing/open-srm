package org.opensrm.identity;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
abstract class CollaborationHttpAcceptance extends PurchasingHttpAcceptance {
 String collabConnection,collabOrder,scheduleOne,scheduleTwo,schedulePlantTwo,otherOrder,otherSchedule,approveOrder,approveSchedule,supplierUser,buyerUser,peerUser,confirmation60,lateId,withdrawId,rescheduleId,pendingOld;
 final String collaborationPassword=UUID.randomUUID().toString();
 final HttpClient supplierClient=client(),buyerClient=client(),peerClient=client();
 static HttpClient client() {return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
 String createActor(String login,String role,HttpClient client) throws Exception {
  String id=id(ok(call(admin,"POST","/users",Map.of("login",login,"displayName","Collaboration User "+login,"password",collaborationPassword,"roles",List.of(role))),201));
  grant(id,"order.read",companyA,null,null);
  if(role.equals("SUPPLIER")) ok(send(admin,"PUT","/users/"+id+"/supplier-binding",Map.of("supplierId",supplierA),true,UUID.randomUUID().toString(),etag(admin,"/users/"+id)),200);
  ok(call(client,"POST","/auth/login",Map.of("login",login,"password",collaborationPassword)),200);return id;
 }
 JsonNode order(String id) throws Exception {return get(supplierClient,"/purchase-orders/"+id);}
 String schedule(String order,int item,int line) throws Exception {return id(order(order).get("items").get(item).get("schedules").get(line));}
 String date(String order,int item,int line,int days) throws Exception {return LocalDate.parse(order(order).get("items").get(item).get("schedules").get(line).get("requestedDate").asString()).plusDays(days).toString();}
 Map<String,Object> confirmBody(String order,String schedule,String quantity,String date) throws Exception {return Map.of("scheduleId",schedule,"quantity",quantity,"promisedDate",date,"sourceRevision",order(order).get("sourceRevision").asString(),"reason","Virtual delivery reason");}
 JsonNode confirm(String order,String schedule,String quantity,String date) throws Exception {return ok(send(supplierClient,"POST","/purchase-orders/"+order+"/confirmations",confirmBody(order,schedule,quantity,date),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+order)),201);}
 JsonNode decision(String change,String status) throws Exception {return ok(send(buyerClient,"POST","/delivery-changes/"+change+"/decision",Map.of("decision",status,"reason","Virtual purchasing decision"),true,UUID.randomUUID().toString(),etag(buyerClient,"/delivery-changes/"+change)),200);}
 String changeFor(String confirmation) {return db.queryForObject("SELECT id::text FROM delivery_change WHERE confirmation_id=? AND status='SUBMITTED'",String.class,UUID.fromString(confirmation));}
 long count(String table) {return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
 @Test @Order(60) void supplierAndBuyerNeedSeparateExplicitScopes() throws Exception {
  collabConnection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Collaboration Mock","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));sync(collabConnection,"CONFIRMATION");
  collabOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(collabConnection));
  otherOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1003'",String.class,UUID.fromString(collabConnection));
  approveOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1004'",String.class,UUID.fromString(collabConnection));
  supplierUser=createActor("collab-supplier","SUPPLIER",supplierClient);buyerUser=createActor("collab-buyer","BUYER",buyerClient);peerUser=createActor("collab-peer","SUPPLIER",peerClient);
  scheduleOne=schedule(collabOrder,0,0);scheduleTwo=schedule(collabOrder,0,1);otherSchedule=schedule(otherOrder,0,0);approveSchedule=schedule(approveOrder,0,0);
  assertEquals(404,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,scheduleOne,"1",date(collabOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());
  grant(supplierUser,"confirmation.submit",companyA,plantA,orgA);grant(peerUser,"confirmation.submit",companyA,null,null);grant(buyerUser,"delivery.approve",companyA,null,null);
  assertEquals(403,send(buyerClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,scheduleOne,"1",date(collabOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(buyerClient,"/purchase-orders/"+collabOrder)).statusCode());
 }
 @Test @Order(61) void partialSixtyPlusFortyCreatesExactlyOnePlanEach() throws Exception {
  var a=confirm(collabOrder,scheduleOne,"60",date(collabOrder,0,0,0));confirmation60=id(a);assertEquals("EFFECTIVE",a.get("status").asString());confirm(collabOrder,scheduleOne,"40",date(collabOrder,0,0,-1));
  var summary=order(collabOrder).get("items").get(0).get("schedules").get(0);assertEquals("100.000000",summary.get("effectiveQuantity").asString());assertEquals("0.000000",summary.get("unconfirmedQuantity").asString());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM delivery_plan WHERE schedule_id=? AND status='PLANNED'",Integer.class,UUID.fromString(scheduleOne)));assertEquals(0,new java.math.BigDecimal("100").compareTo(db.queryForObject("SELECT sum(quantity) FROM delivery_plan WHERE schedule_id=?",java.math.BigDecimal.class,UUID.fromString(scheduleOne))));
 }
 @Test @Order(62) void invalidQuantityOverBudgetAndStaleEtagLeaveNoEffects() throws Exception {
  long c=count("confirmation"),a=count("audit_event"),o=count("outbox_event"),i=count("idempotency_record");
  for(String qty:List.of("0","0.000000","0.000001")) assertTrue(Set.of(409,422).contains(send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,scheduleOne,qty,date(collabOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode()));
  for(String qty:List.of("-1","1.0000001","1e2","10000000000000")) assertEquals(400,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,scheduleTwo,qty,date(collabOrder,0,1,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());
  assertEquals(412,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,scheduleTwo,"1",date(collabOrder,0,1,0)),true,UUID.randomUUID().toString(),"\"999999\"").statusCode());
  assertEquals(c,count("confirmation"));assertEquals(a,count("audit_event"));assertEquals(o,count("outbox_event"));assertEquals(i,count("idempotency_record"));
 }
 @Test @Order(63) void pendingDelayReservesQuantityAndRejectionReleasesIt() throws Exception {
  var late=confirm(otherOrder,otherSchedule,"30",date(otherOrder,0,0,5));assertEquals("SUBMITTED",late.get("status").asString());String change=changeFor(id(late));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM delivery_plan WHERE confirmation_id=?",Integer.class,UUID.fromString(id(late))));confirm(otherOrder,otherSchedule,"30",date(otherOrder,0,0,0));
  assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+otherOrder+"/confirmations",confirmBody(otherOrder,otherSchedule,"1",date(otherOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+otherOrder)).statusCode());
  decision(change,"REJECTED");assertEquals("REJECTED",get(supplierClient,"/confirmations/"+id(late)).get("status").asString());confirm(otherOrder,otherSchedule,"30",date(otherOrder,0,0,0));
 }
 @Test @Order(64) void lateApprovalHasImmutableDecisionAndExactDeliveryPlan() throws Exception {
  var c=confirm(approveOrder,approveSchedule,"20",date(approveOrder,0,0,7));lateId=changeFor(id(c));var body=Map.of("decision","APPROVED","reason","Virtual approval");String tag=etag(buyerClient,"/delivery-changes/"+lateId);
  var first=send(buyerClient,"POST","/delivery-changes/"+lateId+"/decision",body,true,"approval-idempotent-01",tag);ok(first,200);assertEquals(first.body(),send(buyerClient,"POST","/delivery-changes/"+lateId+"/decision",body,true,"approval-idempotent-01",tag).body());
  assertEquals(1,get(buyerClient,"/delivery-changes/"+lateId+"/approval-events").size());assertEquals("EFFECTIVE",get(supplierClient,"/confirmations/"+id(c)).get("status").asString());
  assertEquals(date(approveOrder,0,0,7),db.queryForObject("SELECT due_date::text FROM delivery_plan WHERE confirmation_id=? AND status='PLANNED'",String.class,UUID.fromString(id(c))));
  assertEquals(409,send(buyerClient,"POST","/delivery-changes/"+lateId+"/decision",body,true,UUID.randomUUID().toString(),etag(buyerClient,"/delivery-changes/"+lateId)).statusCode());
 }
 @Test @Order(65) void requesterCanWithdrawButPeerCannot() throws Exception {
  var c=confirm(collabOrder,scheduleTwo,"20",date(collabOrder,0,1,4));withdrawId=changeFor(id(c));String path="/delivery-changes/"+withdrawId;
  assertEquals(403,send(peerClient,"POST",path+"/withdraw",null,true,UUID.randomUUID().toString(),etag(peerClient,path)).statusCode());ok(send(supplierClient,"POST",path+"/withdraw",null,true,UUID.randomUUID().toString(),etag(supplierClient,path)),200);
  assertEquals("REJECTED",get(supplierClient,"/confirmations/"+id(c)).get("status").asString());confirm(collabOrder,scheduleTwo,"40",date(collabOrder,0,1,0));
 }
 @Test @Order(66) void rescheduleKeepsOldPromiseUntilApprovedAndNeverDoublesQuantity() throws Exception {
  String before=get(supplierClient,"/confirmations/"+confirmation60).get("promisedDate").asString();var input=Map.of("confirmationId",confirmation60,"sourceRevision","mock-r1","proposedDate",LocalDate.parse(before).plusDays(10).toString(),"reason","Virtual reschedule");
  var d=ok(send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/delivery-changes",input,true,"reschedule-0001",etag(supplierClient,"/purchase-orders/"+collabOrder)),201);rescheduleId=id(d);assertEquals(before,get(supplierClient,"/confirmations/"+confirmation60).get("promisedDate").asString());
  assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/delivery-changes",input,true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());decision(rescheduleId,"APPROVED");
  assertEquals(input.get("proposedDate"),get(supplierClient,"/confirmations/"+confirmation60).get("promisedDate").asString());assertEquals(2,db.queryForObject("SELECT count(*) FROM delivery_plan WHERE confirmation_id=?",Integer.class,UUID.fromString(confirmation60)));assertEquals(1,db.queryForObject("SELECT count(*) FROM delivery_plan WHERE confirmation_id=? AND status='PLANNED'",Integer.class,UUID.fromString(confirmation60)));
  assertEquals("100.000000",order(collabOrder).get("items").get(0).get("schedules").get(0).get("effectiveQuantity").asString());
 }
 @Test @Order(67) void mistakenSupplierApprovalCapabilityStillCannotSelfApprove() throws Exception {
  var c=confirm(approveOrder,approveSchedule,"5",date(approveOrder,0,0,9));String change=changeFor(id(c));grant(supplierUser,"delivery.approve",companyA,null,null);
  db.update("INSERT INTO role_permission(role_code,permission) VALUES ('SUPPLIER','delivery.approve')");
  try {var r=send(supplierClient,"POST","/delivery-changes/"+change+"/decision",Map.of("decision","APPROVED","reason","Virtual self attempt"),true,UUID.randomUUID().toString(),etag(supplierClient,"/delivery-changes/"+change));assertEquals(403,r.statusCode());assertEquals("SELF_APPROVAL_FORBIDDEN",json.readTree(r.body()).get("code").asString());}
  finally {db.update("DELETE FROM role_permission WHERE role_code='SUPPLIER' AND permission='delivery.approve'");}
  decision(change,"REJECTED");
 }
 @Test @Order(68) void plantScopeAndOrderReferenceCannotBeSpliced() throws Exception {
  schedulePlantTwo=schedule(collabOrder,1,0);
  assertEquals(404,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,schedulePlantTwo,"1",date(collabOrder,1,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());
  assertEquals(404,send(supplierClient,"POST","/purchase-orders/"+otherOrder+"/confirmations",confirmBody(otherOrder,scheduleTwo,"1",date(otherOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+otherOrder)).statusCode());
 }
 @Test @Order(69) void concurrentConfirmationsDoNotExceedScheduleQuantity() throws Exception {
  grant(supplierUser,"confirmation.submit",companyA,plantA2,orgA);String order2=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1002'",String.class,UUID.fromString(collabConnection));String schedule=schedule(order2,0,0),tag=etag(supplierClient,"/purchase-orders/"+order2);var body=confirmBody(order2,schedule,"40",date(order2,0,0,0));
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {var a=executor.submit(()->send(supplierClient,"POST","/purchase-orders/"+order2+"/confirmations",body,true,"parallel-confirm-01",tag));var b=executor.submit(()->send(peerClient,"POST","/purchase-orders/"+order2+"/confirmations",body,true,"parallel-confirm-02",tag));var codes=List.of(a.get().statusCode(),b.get().statusCode());assertTrue(codes.contains(201));assertTrue(codes.contains(412));}
  assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+order2+"/confirmations",confirmBody(order2,schedule,"21",date(order2,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+order2)).statusCode());confirm(order2,schedule,"20",date(order2,0,0,0));
 }
 @Test @Order(70) void revokedSubmissionScopeDeniesReplay() throws Exception {
  var body=confirmBody(approveOrder,approveSchedule,"1",date(approveOrder,0,0,0));String tag=etag(supplierClient,"/purchase-orders/"+approveOrder);ok(send(supplierClient,"POST","/purchase-orders/"+approveOrder+"/confirmations",body,true,"revoke-confirm-0001",tag),201);
  for(var g:get(admin,"/users/"+supplierUser+"/grants")) if(g.get("permission").asString().equals("confirmation.submit")) ok(send(admin,"DELETE","/users/"+supplierUser+"/grants/"+id(g),null,true,UUID.randomUUID().toString(),etag(admin,"/users/"+supplierUser)),200);
  assertEquals(404,send(supplierClient,"POST","/purchase-orders/"+approveOrder+"/confirmations",body,true,"revoke-confirm-0001",tag).statusCode());grant(supplierUser,"confirmation.submit",companyA,null,null);
 }
 @Test @Order(71) void frozenSupplierCannotSubmitOrApprovePendingDelay() throws Exception {
  var c=confirm(approveOrder,approveSchedule,"2",date(approveOrder,0,0,12));String pending=changeFor(id(c));var s=get(admin,"/suppliers/"+supplierA);
  write(admin,"PATCH","/suppliers/"+supplierA,Map.of("name",s.get("name").asString(),"status","FROZEN"),200);
  try {assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+approveOrder+"/confirmations",confirmBody(approveOrder,approveSchedule,"1",date(approveOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+approveOrder)).statusCode());assertEquals(409,send(buyerClient,"POST","/delivery-changes/"+pending+"/decision",Map.of("decision","APPROVED","reason","Virtual frozen attempt"),true,UUID.randomUUID().toString(),etag(buyerClient,"/delivery-changes/"+pending)).statusCode());}
  finally {write(admin,"PATCH","/suppliers/"+supplierA,Map.of("name",s.get("name").asString(),"status","ACTIVE"),200);}
 }
 @Test @Order(72) void approvalScopeAndSupplierBindingProtectDetailsAndCounts() throws Exception {
  String user=createActor("collab-buyer-b","BUYER",client());var outsider=client();ok(call(outsider,"POST","/auth/login",Map.of("login","collab-buyer-b","password",collaborationPassword)),200);grant(user,"delivery.approve",companyB,null,null);
  assertEquals(404,send(outsider,"POST","/delivery-changes/"+lateId+"/decision",Map.of("decision","APPROVED","reason","Virtual wrong company"),true,UUID.randomUUID().toString(),etag(outsider,"/delivery-changes/"+lateId)).statusCode());
  assertEquals(0,get(external,"/confirmations?companyId="+companyA).get("total").asInt());assertEquals(404,call(external,"GET","/confirmations/"+confirmation60,null).statusCode());assertEquals(405,call(supplierClient,"POST","/delivery-plans",Map.of()).statusCode());
 }
 @Test @Order(73) void newErpVersionAtomicallySupersedesCollaborationAndRejectsOldSubmissions() throws Exception {
  var pending=confirm(otherOrder,schedule(otherOrder,0,1),"10",date(otherOrder,0,1,4));pendingOld=changeFor(id(pending));String oldPlan=id(get(supplierClient,"/delivery-plans?orderId="+collabOrder).get("items").get(0));
  sync(collabConnection,"UPDATE");assertEquals("SUPERSEDED",get(supplierClient,"/confirmations/"+confirmation60).get("status").asString());assertEquals("SUPERSEDED",get(supplierClient,"/delivery-changes/"+pendingOld).get("status").asString());assertEquals("SUPERSEDED",get(supplierClient,"/delivery-plans/"+oldPlan).get("status").asString());assertEquals("APPROVED",get(supplierClient,"/delivery-changes/"+lateId).get("status").asString());
  assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",Map.of("scheduleId",scheduleOne,"sourceRevision","mock-r1","quantity","1","promisedDate","2027-03-01"),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());
  assertEquals(409,send(buyerClient,"POST","/delivery-changes/"+pendingOld+"/decision",Map.of("decision","APPROVED","reason","Virtual stale approval"),true,UUID.randomUUID().toString(),etag(buyerClient,"/delivery-changes/"+pendingOld)).statusCode());
  var current=order(collabOrder).get("items").get(0).get("schedules").get(0);assertEquals("55.000000",current.get("unconfirmedQuantity").asString());
 }
 @Test @Order(74) void cancellationInvalidatesPlansWithoutDeletingHistory() throws Exception {
  var c=confirm(collabOrder,schedule(collabOrder,0,0),"10",date(collabOrder,0,0,0));sync(collabConnection,"CANCEL");assertEquals("SUPERSEDED",get(supplierClient,"/confirmations/"+id(c)).get("status").asString());
  String schedule=schedule(collabOrder,0,0);assertEquals(409,send(supplierClient,"POST","/purchase-orders/"+collabOrder+"/confirmations",confirmBody(collabOrder,schedule,"1",date(collabOrder,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+collabOrder)).statusCode());assertTrue(count("approval_event")>0);assertTrue(count("outbox_event")>0);
 }
 @Test @Order(75) void rejectedAndWithdrawnReschedulesKeepOriginalEffectivePlan() throws Exception {
  String connection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Retained Promise Mock","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA))),201));sync(connection,"CONFIRMATION");
  String order=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(connection)),schedule=schedule(order,0,0),due=date(order,0,0,0);String c=id(confirm(order,schedule,"10",due));
  for(boolean reject:List.of(true,false)) {
   var input=Map.of("confirmationId",c,"sourceRevision","mock-r1","proposedDate",LocalDate.parse(due).plusDays(5).toString(),"reason","Virtual retained commitment");var d=ok(send(supplierClient,"POST","/purchase-orders/"+order+"/delivery-changes",input,true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+order)),201);
   if(reject) decision(id(d),"REJECTED");else ok(send(supplierClient,"POST","/delivery-changes/"+id(d)+"/withdraw",null,true,UUID.randomUUID().toString(),etag(supplierClient,"/delivery-changes/"+id(d))),200);
   var value=get(supplierClient,"/confirmations/"+c);assertEquals("EFFECTIVE",value.get("status").asString());assertEquals(due,value.get("promisedDate").asString());assertEquals(1,db.queryForObject("SELECT count(*) FROM delivery_plan WHERE confirmation_id=? AND status='PLANNED'",Integer.class,UUID.fromString(c)));assertEquals("90.000000",order(order).get("items").get(0).get("schedules").get(0).get("unconfirmedQuantity").asString());
  }
 }
 @Test @Order(76) void sourceConflictFreezesExistingCommitmentsAndStopsPendingOutbox() throws Exception {
  String connection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Frozen Commitment Mock","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA))),201));sync(connection,"CONFIRMATION");String order=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(connection)),schedule=schedule(order,0,0);String c=id(confirm(order,schedule,"10",date(order,0,0,0))),pending=id(confirm(order,schedule,"5",date(order,0,0,5)));String change=changeFor(pending);
  sync(connection,"CONFLICT");assertEquals("EFFECTIVE",get(supplierClient,"/confirmations/"+c).get("status").asString());assertEquals(0,db.queryForObject("SELECT count(*) FROM outbox_event WHERE order_id=? AND status='PENDING'",Integer.class,UUID.fromString(order)));
  var response=send(supplierClient,"POST","/purchase-orders/"+order+"/confirmations",confirmBody(order,schedule,"1",date(order,0,0,0)),true,UUID.randomUUID().toString(),etag(supplierClient,"/purchase-orders/"+order));assertEquals("CONSISTENCY_HOLD",json.readTree(response.body()).get("code").asString());
  assertEquals(409,send(buyerClient,"POST","/delivery-changes/"+change+"/decision",Map.of("decision","APPROVED","reason","Virtual frozen source attempt"),true,UUID.randomUUID().toString(),etag(buyerClient,"/delivery-changes/"+change)).statusCode());
 }
}
