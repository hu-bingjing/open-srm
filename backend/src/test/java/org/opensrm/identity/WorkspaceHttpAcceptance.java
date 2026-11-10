package org.opensrm.identity;
import java.util.*;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.opensrm.notification.NotificationService;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

abstract class WorkspaceHttpAcceptance extends SapHttpAcceptance {
 @Autowired NotificationService notifications;
 @DynamicPropertySource static void notificationConfig(DynamicPropertyRegistry p) {p.add("opensrm.notification.initial-delay",()->"3600000");}
 String wsConnection,wsOrder,wsSchedule,wsUser,wsConfirmation,wsNotice,wsDelivery,wsLate;
 final HttpClient ws=client(),noBusiness=client();
 void drain(){for(int n=0;n<20;n++)notifications.tick();}
 String deliveryFor(String aggregate){return db.queryForObject("SELECT d.id::text FROM notification_delivery d JOIN outbox_event e ON e.id=d.origin_id WHERE e.aggregate_id=? AND e.event_type='confirmation.effective'",String.class,UUID.fromString(aggregate));}
 @Test @Order(120) void workspaceRequiresAuthenticationAndCannotInferUnscopedBusiness()throws Exception {
  assertEquals(401,call(client(),"GET","/dashboard",null).statusCode());
  String login="workspace-ordinary",password=UUID.randomUUID().toString();ok(call(admin,"POST","/users",Map.of("login",login,"displayName","Workspace User","password",password,"roles",List.of("USER"))),201);ok(call(noBusiness,"POST","/auth/login",Map.of("login",login,"password",password)),200);
  assertEquals(0,get(noBusiness,"/dashboard").get("orders").asInt());assertEquals(0,get(noBusiness,"/notifications").get("total").asInt());assertEquals(403,call(noBusiness,"GET","/statistics",null).statusCode());assertEquals(403,call(noBusiness,"GET","/notification-deliveries",null).statusCode());
 }
 @Test @Order(121) void realBusinessEventsProduceScopedDeduplicatedNotices()throws Exception {
  wsConnection=id(ok(call(admin,"POST","/erp-connections",Map.of("name","Workspace Mock","companyId",companyA,"purchasingOrgId",orgA,"supplierId",supplierA,"plantIds",List.of(plantA,plantA2))),201));sync(wsConnection,"CONFIRMATION");
  wsOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(wsConnection));
  wsUser=createActor("workspace-supplier","SUPPLIER",ws);grant(wsUser,"confirmation.submit",companyA,plantA,orgA);grant(wsUser,"asn.manage",companyA,plantA,orgA);
  var o=get(ws,"/purchase-orders/"+wsOrder);var schedule=o.get("items").get(0).get("schedules").get(0);wsSchedule=id(schedule);
  wsConfirmation=id(ok(send(ws,"POST","/purchase-orders/"+wsOrder+"/confirmations",Map.of("scheduleId",wsSchedule,"sourceRevision",o.get("sourceRevision").asString(),"quantity","25","promisedDate",schedule.get("requestedDate").asString()),true,UUID.randomUUID().toString(),etag(ws,"/purchase-orders/"+wsOrder)),201));
  drain();wsDelivery=deliveryFor(wsConfirmation);assertEquals("DELIVERED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,UUID.fromString(wsDelivery)));
  wsNotice=db.queryForObject("SELECT id::text FROM notification WHERE delivery_id=? AND recipient_id=?",String.class,UUID.fromString(wsDelivery),UUID.fromString(wsUser));
  long before=count("notification");db.update("UPDATE notification_delivery SET status='PENDING' WHERE id=?",UUID.fromString(wsDelivery));drain();assertEquals(before,count("notification"));assertEquals(1,db.queryForObject("SELECT count(*) FROM notification WHERE delivery_id=? AND recipient_id=?",Integer.class,UUID.fromString(wsDelivery),UUID.fromString(wsUser)));
  assertEquals(404,call(peerClient,"GET","/notifications/"+wsNotice,null).statusCode());assertEquals(404,call(external,"GET","/notifications/"+wsNotice,null).statusCode());
 }
 @Test @Order(122) void ownReadUsesVersionIdempotencyAndAtomicAudit()throws Exception {
  assertEquals(412,send(ws,"POST","/notifications/"+wsNotice+"/read",null,true,"notice-stale-version","\"999\"").statusCode());
  String key="notice-own-read-key",tag=etag(ws,"/notifications/"+wsNotice);long audit=count("audit_event");var first=send(ws,"POST","/notifications/"+wsNotice+"/read",null,true,key,tag);ok(first,200);assertNotNull(json.readTree(first.body()).get("readAt").asString());assertEquals(first.body(),send(ws,"POST","/notifications/"+wsNotice+"/read",null,true,key,tag).body());assertEquals(audit+1,count("audit_event"));
  assertEquals(404,send(peerClient,"POST","/notifications/"+wsNotice+"/read",null,true,UUID.randomUUID().toString(),tag).statusCode());
 }
 @Test @Order(123) void revocationHidesMessagesCountsAndCachedReadReplay()throws Exception {
  db.update("UPDATE organization_grant SET active=false WHERE user_id=? AND permission='order.read'",UUID.fromString(wsUser));
  try {assertEquals(0,get(ws,"/notifications").get("total").asInt());assertEquals(0,get(ws,"/notifications/unread-count").get("count").asInt());assertEquals(0,get(ws,"/dashboard").get("unreadNotifications").asInt());assertEquals(404,call(ws,"GET","/notifications/"+wsNotice,null).statusCode());assertEquals(404,send(ws,"POST","/notifications/"+wsNotice+"/read",null,true,"notice-own-read-key","\"0\"").statusCode());}
  finally{db.update("UPDATE organization_grant SET active=true WHERE user_id=? AND permission='order.read'",UUID.fromString(wsUser));}
 }
 @Test @Order(124) void supplierUnbindingAndPlantScopeCannotBeBypassed()throws Exception {
  db.update("DELETE FROM user_supplier WHERE user_id=?",UUID.fromString(wsUser));try {assertEquals(0,get(ws,"/notifications").get("total").asInt());assertEquals(0,get(ws,"/statistics").size());}finally{db.update("INSERT INTO user_supplier(user_id,deployment_id,supplier_id) SELECT ?,deployment_id,? FROM app_guard WHERE id=1",UUID.fromString(wsUser),UUID.fromString(supplierA));}
  db.update("UPDATE organization_grant SET plant_id=? WHERE user_id=? AND permission='order.read'",UUID.fromString(plantA2),UUID.fromString(wsUser));try{assertEquals(404,call(ws,"GET","/notifications/"+wsNotice,null).statusCode());for(var row:get(ws,"/statistics"))assertEquals(plantA2,row.get("plantId").asString());}finally{db.update("UPDATE organization_grant SET plant_id=NULL WHERE user_id=? AND permission='order.read'",UUID.fromString(wsUser));}
 }
 @Test @Order(125) void statisticsReconcileIndependentlyWithoutJoiningLedgerFanout()throws Exception {
  var rows=get(admin,"/statistics?companyId="+companyA);BigDecimal ordered=BigDecimal.ZERO,received=BigDecimal.ZERO,shipped=BigDecimal.ZERO,transit=BigDecimal.ZERO;
  for(var row:rows){ordered=ordered.add(new BigDecimal(row.get("orderedQuantity").asString()));received=received.add(new BigDecimal(row.get("netReceiptQuantity").asString()));shipped=shipped.add(new BigDecimal(row.get("submittedQuantity").asString()));transit=transit.add(new BigDecimal(row.get("inTransitQuantity").asString()));assertTrue(new BigDecimal(row.get("inTransitQuantity").asString()).signum()>=0);}
  assertEquals(0,ordered.compareTo(db.queryForObject("SELECT COALESCE(sum(s.ordered_quantity),0) FROM purchase_order o JOIN po_item i ON i.revision_id=o.current_revision_id JOIN po_schedule s ON s.item_id=i.id WHERE o.company_id=?",BigDecimal.class,UUID.fromString(companyA))));
  assertEquals(0,received.compareTo(db.queryForObject("SELECT COALESCE(sum(CASE e.event_type WHEN 'POSTED' THEN e.quantity ELSE -e.quantity END),0) FROM receipt_event e JOIN purchase_order o ON o.id=e.order_id WHERE o.company_id=?",BigDecimal.class,UUID.fromString(companyA))));
  assertEquals(0,shipped.compareTo(db.queryForObject("SELECT COALESCE(sum(l.quantity),0) FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN purchase_order o ON o.id=l.order_id WHERE h.status='SUBMITTED' AND o.company_id=?",BigDecimal.class,UUID.fromString(companyA))));
  assertEquals(0,transit.compareTo(db.queryForObject("SELECT COALESCE(sum(GREATEST(l.quantity-COALESCE(a.net,0),0)),0) FROM asn_line l JOIN asn h ON h.id=l.asn_id JOIN purchase_order o ON o.id=l.order_id LEFT JOIN (SELECT ra.asn_line_id,sum(CASE e.event_type WHEN 'POSTED' THEN ra.quantity ELSE -ra.quantity END) net FROM receipt_allocation ra JOIN receipt_event e ON e.id=ra.event_id GROUP BY ra.asn_line_id) a ON a.asn_line_id=l.id WHERE h.status='SUBMITTED' AND o.company_id=?",BigDecimal.class,UUID.fromString(companyA))));
  assertEquals(0,get(ws,"/statistics?companyId="+companyB).size());assertEquals(0,get(ws,"/statistics?plantId="+plantB).size());assertEquals(0,get(ws,"/statistics?supplierId="+supplierB).size());assertEquals(0,get(ws,"/statistics?purchasingOrgId="+UUID.randomUUID()).size());
 }
 @Test @Order(126) void dashboardCountsOnlyCurrentActionableObjects()throws Exception {
  var o=get(ws,"/purchase-orders/"+wsOrder);var s=o.get("items").get(0).get("schedules").get(1);
  var late=ok(send(ws,"POST","/purchase-orders/"+wsOrder+"/confirmations",Map.of("scheduleId",id(s),"sourceRevision",o.get("sourceRevision").asString(),"quantity","10","promisedDate",java.time.LocalDate.parse(s.get("requestedDate").asString()).plusDays(3).toString(),"reason","Virtual workspace delay"),true,UUID.randomUUID().toString(),etag(ws,"/purchase-orders/"+wsOrder)),201);wsLate=changeFor(id(late));
  assertTrue(get(ws,"/dashboard").get("unconfirmedSchedules").asLong()>0);var buyer=get(buyerClient,"/dashboard");assertTrue(buyer.get("pendingApprovals").asLong()>0);assertEquals(0,get(ws,"/dashboard").get("pendingApprovals").asLong());
  long expected=db.queryForObject("SELECT count(DISTINCT o.id) FROM purchase_order o WHERE o.company_id=? AND EXISTS(SELECT 1 FROM po_item i WHERE i.revision_id=o.current_revision_id)",Long.class,UUID.fromString(companyA));assertEquals(expected,get(ws,"/dashboard").get("orders").asLong());
 }
 @Test @Order(127) void staleAggregateNeverBecomesANewNotification()throws Exception {
  var c=ok(send(ws,"POST","/purchase-orders/"+wsOrder+"/confirmations",Map.of("scheduleId",wsSchedule,"sourceRevision","mock-r1","quantity","1","promisedDate",get(ws,"/purchase-orders/"+wsOrder).get("items").get(0).get("schedules").get(0).get("requestedDate").asString()),true,UUID.randomUUID().toString(),etag(ws,"/purchase-orders/"+wsOrder)),201);
  db.update("UPDATE confirmation SET lock_version=lock_version+1 WHERE id=?",UUID.fromString(id(c)));drain();String d=deliveryFor(id(c));assertEquals("SUPERSEDED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,UUID.fromString(d)));assertEquals(0,db.queryForObject("SELECT count(*) FROM notification WHERE delivery_id=?",Integer.class,UUID.fromString(d)));
 }
 @Test @Order(128) void deliveryFailureRollsBackRecipientsAndCanRetryWithoutDuplicates()throws Exception {
  var c=ok(send(ws,"POST","/purchase-orders/"+wsOrder+"/confirmations",Map.of("scheduleId",wsSchedule,"sourceRevision","mock-r1","quantity","1","promisedDate",get(ws,"/purchase-orders/"+wsOrder).get("items").get(0).get("schedules").get(0).get("requestedDate").asString()),true,UUID.randomUUID().toString(),etag(ws,"/purchase-orders/"+wsOrder)),201);
  UUID event=db.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=? AND event_type='confirmation.effective'",UUID.class,UUID.fromString(id(c)));UUID d=UUID.randomUUID();db.update("INSERT INTO notification_delivery(id,deployment_id,origin_kind,origin_id,origin_version,event_type,order_id,source_revision) SELECT ?,deployment_id,'COLLABORATION',id,aggregate_version,event_type,order_id,source_revision FROM outbox_event WHERE id=?",d,event);
  db.execute("CREATE FUNCTION workspace_fail_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.delivery_id='"+d+"'::uuid AND NEW.recipient_id='"+wsUser+"'::uuid THEN RAISE EXCEPTION 'synthetic dispatch failure'; END IF; RETURN NEW; END $$");db.execute("CREATE TRIGGER workspace_notice_failure BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION workspace_fail_notice()");
  try {for(int i=0;i<5;i++){db.update("UPDATE notification_delivery SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",d);drain();}assertEquals("FAILED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,d));assertEquals(0,db.queryForObject("SELECT count(*) FROM notification WHERE delivery_id=?",Integer.class,d));assertEquals(403,send(ws,"POST","/notification-deliveries/"+d+"/retry",null,true,UUID.randomUUID().toString(),"\"5\"").statusCode());assertEquals(412,send(admin,"POST","/notification-deliveries/"+d+"/retry",null,true,UUID.randomUUID().toString(),"\"999\"").statusCode());var first=send(admin,"POST","/notification-deliveries/"+d+"/retry",null,true,"workspace-delivery-retry","\"5\"");ok(first,202);assertEquals(first.body(),send(admin,"POST","/notification-deliveries/"+d+"/retry",null,true,"workspace-delivery-retry","\"5\"").body());}
  finally{db.execute("DROP TRIGGER workspace_notice_failure ON notification");db.execute("DROP FUNCTION workspace_fail_notice()");}
  drain();assertEquals("DELIVERED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,d));long before=count("notification");db.update("UPDATE notification_delivery SET status='PENDING' WHERE id=?",d);drain();assertEquals(before,count("notification"));
 }
 @Test @Order(134) void syncFailureHasOnlyConnectionOwnerRecipientAndNoRawError()throws Exception {
  var job=waitJob(id(ok(call(admin,"POST","/sync-jobs",Map.of("connectionId",wsConnection,"entityType","PURCHASE_ORDER","scenario","PERMANENT_FAIL")),202)));assertEquals("FAILED",job.get("status").asString());drain();
  UUID delivery=db.queryForObject("SELECT id FROM notification_delivery WHERE job_id=? AND origin_version=?",UUID.class,UUID.fromString(id(job)),job.get("version").asLong());assertEquals(1,db.queryForObject("SELECT count(*) FROM notification WHERE delivery_id=?",Integer.class,delivery));assertEquals(adminId,db.queryForObject("SELECT recipient_id::text FROM notification WHERE delivery_id=?",String.class,delivery));
  assertTrue(get(admin,"/dashboard").get("failedSyncJobs").asLong()>0);assertEquals(0,get(ws,"/dashboard").get("failedSyncJobs").asLong());
 }
 @Test @Order(130) void overduePlansUseCompanyDateAndExcludeFullyShippedOrStalePlans()throws Exception {
  UUID plan=db.queryForObject("SELECT id FROM delivery_plan WHERE confirmation_id=?",UUID.class,UUID.fromString(wsConfirmation));
  var original=db.queryForObject("SELECT due_date FROM delivery_plan WHERE id=?",java.sql.Date.class,plan);
  try {db.update("UPDATE delivery_plan SET due_date=(CURRENT_TIMESTAMP AT TIME ZONE (SELECT timezone FROM company WHERE id=?))::date-1 WHERE id=?",UUID.fromString(companyA),plan);assertTrue(get(ws,"/dashboard").get("overduePlans").asLong()>0);db.update("UPDATE delivery_plan SET status='SHIPPED' WHERE id=?",plan);for(var task:get(ws,"/dashboard").get("tasks"))assertNotEquals(plan.toString(),task.get("id").asString());}
  finally{db.update("UPDATE delivery_plan SET due_date=?,status='PLANNED' WHERE id=?",original,plan);}
 }
 @Test @Order(131) void purchasingTupleAndInactiveOrganizationHideStatisticsAndNotifications()throws Exception {
  db.update("UPDATE organization_grant SET purchasing_org_id=? WHERE user_id=? AND permission='order.read'",UUID.fromString(orgA2),UUID.fromString(wsUser));
  try{assertEquals(404,call(ws,"GET","/notifications/"+wsNotice,null).statusCode());assertEquals(0,get(ws,"/statistics?companyId="+companyA+"&purchasingOrgId="+orgA).size());}finally{db.update("UPDATE organization_grant SET purchasing_org_id=NULL WHERE user_id=? AND permission='order.read'",UUID.fromString(wsUser));}
  db.update("UPDATE company SET active=false WHERE id=?",UUID.fromString(companyA));try{assertEquals(0,get(ws,"/statistics").size());assertEquals(0,get(ws,"/notifications/unread-count").get("count").asInt());assertEquals(404,call(ws,"GET","/notifications/"+wsNotice,null).statusCode());}finally{db.update("UPDATE company SET active=true WHERE id=?",UUID.fromString(companyA));}
 }

 @Test @Order(132) void differentUnitsAreReportedInSeparateGroups()throws Exception {
  UUID item=db.queryForObject("SELECT i.id FROM po_item i JOIN purchase_order o ON o.current_revision_id=i.revision_id WHERE o.id=? AND i.plant_id=? LIMIT 1",UUID.class,UUID.fromString(wsOrder),UUID.fromString(plantA2));String unit=db.queryForObject("SELECT unit FROM po_item WHERE id=?",String.class,item);
  db.update("UPDATE po_item SET unit='KG' WHERE id=?",item);
  try{var rows=get(ws,"/statistics?companyId="+companyA);boolean found=false;for(var row:rows)if(row.get("unit").asString().equals("KG")){found=true;assertEquals(plantA2,row.get("plantId").asString());assertTrue(new BigDecimal(row.get("orderedQuantity").asString()).signum()>0);}assertTrue(found);}finally{db.update("UPDATE po_item SET unit=? WHERE id=?",unit,item);}
 }

 @Test @Order(133) void pendingAndFailedDeliveryMonitorCannotLeakAnotherPlant()throws Exception {
  var monitor=client();String actor=createActor("workspace-monitor","ADMIN",monitor);
  db.update("UPDATE organization_grant SET plant_id=? WHERE user_id=? AND permission='order.read'",UUID.fromString(plantA),UUID.fromString(actor));
  var c=confirm(wsOrder,schedule(wsOrder,1,0),"1",date(wsOrder,1,0,0));
  org.springframework.test.util.ReflectionTestUtils.invokeMethod(notifications,"seed");String delivery=deliveryFor(id(c));
  assertNull(db.queryForObject("SELECT plant_id FROM notification_delivery WHERE id=?",UUID.class,UUID.fromString(delivery)));
  for(var row:get(monitor,"/notification-deliveries?size=100").get("items"))assertNotEquals(delivery,row.get("id").asString());
  db.update("UPDATE notification_delivery SET status='FAILED' WHERE id=?",UUID.fromString(delivery));
  assertEquals(404,send(monitor,"POST","/notification-deliveries/"+delivery+"/retry",null,true,UUID.randomUUID().toString(),"\"0\"").statusCode());
  grant(actor,"order.read",companyA,plantA2,orgA);
  ok(send(monitor,"POST","/notification-deliveries/"+delivery+"/retry",null,true,"authorized-plant-retry","\"0\""),202);drain();
 }

}
