package org.opensrm.identity;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.*;
import org.opensrm.adapters.sap.SapFixture;
import tools.jackson.databind.JsonNode;

abstract class SapHttpAcceptance extends ShippingHttpAcceptance {
 static final SapFixture sap=new SapFixture();String sapConnection,sapOrder,multiConnection;
 @DynamicPropertySource static void sapConfig(DynamicPropertyRegistry p){p.add("opensrm.sap.config",()->sap.configFile.toString());}
 @AfterAll void stopSap(){sap.close();}
 Map<String,Object> sapInput(String name){return Map.ofEntries(Map.entry("name",name),Map.entry("companyId",companyA),Map.entry("purchasingOrgId",orgA),Map.entry("supplierId",supplierA),Map.entry("plantIds",List.of(plantA)),Map.entry("adapterType","sap-odata"),Map.entry("sourceSystem","SAP_TEST"),Map.entry("sourceClient","100"),Map.entry("companyExternalKey","COMPANY-A"),Map.entry("purchasingOrgExternalKey","PURCHASING-A"),Map.entry("supplierExternalKey","SUPPLIER-A"),Map.entry("plantMappings",Map.of("PLANT-1",plantA)));}
 JsonNode sapSync(String c,String entity,String scenario)throws Exception{return waitJob(id(ok(call(admin,"POST","/sync-jobs",Map.of("connectionId",c,"entityType",entity,"scenario",scenario)),202)));}
 JsonNode sapOrders()throws Exception{return sapSync(sapConnection,"PURCHASE_ORDER","SAP_READ");}
 JsonNode sapReceipts()throws Exception{return sapSync(sapConnection,"GOODS_RECEIPT","SAP_RECEIPTS");}
 long sapCount(String table){return db.queryForObject("SELECT count(*) FROM "+table+" WHERE connection_id=?",Long.class,UUID.fromString(sapConnection));}
 @Test @Order(100) void sapMetadataJobUsesRealTlsAndLeavesBusinessLedgerUnchanged()throws Exception {
  sapConnection=id(ok(call(admin,"POST","/erp-connections",sapInput("Virtual SAP TLS contract")),201));long before=count("receipt_event");
  var job=sapSync(sapConnection,"METADATA","SAP_METADATA");assertEquals("SUCCEEDED",job.get("status").asString());assertEquals(2,job.get("processed").asInt());assertEquals(before,count("receipt_event"));assertEquals(0,sapCount("purchase_order"));assertEquals(403,call(external,"GET","/erp-adapters",null).statusCode());
 }
 @Test @Order(101) void sapOrdersArePersistedWithActualFieldAndVersionMapping()throws Exception {
  assertEquals("SUCCEEDED",sapOrders().get("status").asString());assertEquals(1,sapCount("purchase_order"));sapOrder=db.queryForObject("SELECT id::text FROM purchase_order WHERE connection_id=? AND external_id='PO-1001'",String.class,UUID.fromString(sapConnection));
  assertEquals("sap-ts-1767225600000",get(admin,"/purchase-orders/"+sapOrder).get("sourceRevision").asString());assertEquals(403,call(external,"GET","/erp-connections/"+sapConnection,null).statusCode());
 }
 @Test @Order(102) void repeatedSapScanDoesNotDuplicateVersions()throws Exception {var j=sapOrders();assertEquals(1,j.get("skipped").asInt());assertEquals(1,db.queryForObject("SELECT count(*) FROM po_revision WHERE order_id=?",Integer.class,UUID.fromString(sapOrder)));}
 @Test @Order(103) void newSapTimestampCreatesImmutableRevision()throws Exception {sap.mode="UPDATE";assertEquals("SUCCEEDED",sapOrders().get("status").asString());assertEquals(2,db.queryForObject("SELECT count(*) FROM po_revision WHERE order_id=?",Integer.class,UUID.fromString(sapOrder)));}
 @Test @Order(104) void sameTimestampChangedPayloadIsQuarantined()throws Exception {sap.mode="CONFLICT";var j=sapOrders();assertEquals("QUARANTINED",j.get("status").asString());assertTrue(get(admin,"/purchase-orders/"+sapOrder).get("consistencyHold").asBoolean());assertEquals("SOURCE_REVISION_CONFLICT",get(admin,"/sync-jobs/"+id(j)+"/issues").get("items").get(0).get("errorCode").asString());}
 @Test @Order(105) void oldUnseenSapRevisionCannotOverwriteCurrentOrder()throws Exception {sap.mode="OLD";assertEquals("QUARANTINED",sapOrders().get("status").asString());assertEquals(2,db.queryForObject("SELECT count(*) FROM po_revision WHERE order_id=?",Integer.class,UUID.fromString(sapOrder)));}
 @Test @Order(106) void receiptWithOnlyOnePlanMapsWithoutAsn()throws Exception {sap.mode="INITIAL";assertEquals("SUCCEEDED",sapReceipts().get("status").asString());assertEquals(1,sapCount("receipt_event"));assertEquals("20.000000",get(admin,"/purchase-orders/"+sapOrder+"/shipping-balances").get(0).get("receivedQuantity").asString());}
 @Test @Order(107) void reversalReferenceRestoresNetQuantityAndReplayIsIdempotent()throws Exception {sap.mode="REVERSAL";assertEquals("SUCCEEDED",sapReceipts().get("status").asString());assertEquals(1,sapReceipts().get("skipped").asInt());sap.mode="CANCEL_FLAG";assertEquals(1,sapReceipts().get("skipped").asInt());assertEquals(2,sapCount("receipt_event"));assertEquals("15.000000",get(admin,"/purchase-orders/"+sapOrder+"/shipping-balances").get(0).get("receivedQuantity").asString());}
 @Test @Order(108) void multiScheduleReceiptCannotGuessSchedule()throws Exception {sap.mode="MULTI";multiConnection=id(ok(call(admin,"POST","/erp-connections",sapInput("Virtual ambiguous schedule")),201));assertEquals("SUCCEEDED",sapSync(multiConnection,"PURCHASE_ORDER","SAP_READ").get("status").asString());var j=sapSync(multiConnection,"GOODS_RECEIPT","SAP_RECEIPTS");assertEquals("QUARANTINED",j.get("status").asString());assertEquals("RECEIPT_SCHEDULE_AMBIGUOUS",get(admin,"/sync-jobs/"+id(j)+"/issues").get("items").get(0).get("errorCode").asString());assertEquals(0,db.queryForObject("SELECT count(*) FROM receipt_event WHERE connection_id=?",Integer.class,UUID.fromString(multiConnection)));}
 @Test @Order(109) void transientSapFailureRetriesFromCommittedPage()throws Exception {sap.mode="FAIL_ONCE";sap.failed.set(false);var j=sapOrders();assertEquals("SUCCEEDED",j.get("status").asString());assertEquals(1,j.get("attempts").asInt());assertEquals(2,j.get("processed").asInt());assertEquals(2,sapCount("purchase_order"));assertTrue(sap.methods.stream().allMatch("GET"::equals));}
 @Test @Order(110) void deniedSapAccessStopsAndCanBeManuallyRetried()throws Exception {sap.status=403;var j=sapSync(sapConnection,"METADATA","SAP_METADATA");assertEquals("FAILED",j.get("status").asString());assertEquals("SAP_AUTHORIZATION_DENIED",j.get("errorCode").asString());assertEquals(0,j.get("attempts").asInt());sap.status=200;sap.mode="INITIAL";ok(send(admin,"POST","/sync-jobs/"+id(j)+"/retry",null,true,UUID.randomUUID().toString(),"\""+j.get("version").asLong()+"\""),202);assertEquals("SUCCEEDED",waitJob(id(j)).get("status").asString());}
 @Test @Order(111) void externalWhitelistCannotBeBoundToWrongLocalCompany()throws Exception {
  for(String permission:List.of("sync.manage","supplier.manage"))if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM organization_grant WHERE user_id=? AND company_id=? AND permission=? AND active AND plant_id IS NULL AND purchasing_org_id IS NULL)",Boolean.class,UUID.fromString(adminId),UUID.fromString(companyB),permission)))grant(adminId,permission,companyB,null,null);
  var input=new HashMap<>(sapInput("Virtual unauthorized remapping"));input.put("companyId",companyB);input.put("plantIds",List.of(plantB));input.put("plantMappings",Map.of("PLANT-1",plantB));String c=id(ok(call(admin,"POST","/erp-connections",input),201));int before=sap.paths.size();var j=sapSync(c,"PURCHASE_ORDER","SAP_READ");assertEquals("FAILED",j.get("status").asString());assertEquals("SAP_MAPPING_NOT_APPROVED",j.get("errorCode").asString());assertEquals(before,sap.paths.size());assertEquals(0,db.queryForObject("SELECT count(*) FROM purchase_order WHERE connection_id=?",Integer.class,UUID.fromString(c)));
 }
}
