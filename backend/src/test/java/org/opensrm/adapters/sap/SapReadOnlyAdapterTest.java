package org.opensrm.adapters.sap;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.opensrm.integration.port.*;
import org.opensrm.integration.port.ErpData.*;

class SapReadOnlyAdapterTest {
 static SapFixture fixture;SapReadOnlyAdapter adapter;
 static final ReadScope SCOPE=new ReadScope("SAP_TEST","100","COMPANY-A","PURCHASING-A","SUPPLIER-A",List.of("PLANT-1"),"A","A1","S-A",Map.of("PLANT-1","A1"));
 @BeforeAll static void start(){fixture=new SapFixture();}
 @AfterAll static void stop(){if(fixture!=null)fixture.close();}
 @BeforeEach void reset(){fixture.mode="INITIAL";fixture.status=200;fixture.methods.clear();fixture.paths.clear();adapter=fixture.adapter();}
 Request request(String cursor){return new Request("SAP_READ",cursor,1,0,1,SCOPE,"sap-"+System.currentTimeMillis());}
 @Test void tlsAndBothMetadataContractsAreVerified(){adapter.verifyMetadata();assertEquals(2,fixture.paths.size());assertTrue(fixture.methods.stream().allMatch("GET"::equals));}
 @Test void realFieldNamesMapHeadItemScheduleAndTimestampVersion(){var p=adapter.fetchPurchaseOrders(request(null));assertTrue(p.issues().isEmpty());var o=p.items().getFirst();assertEquals("sap-ts-1767225600000",o.source().sourceRevision());assertEquals("0001",o.items().getFirst().schedules().getFirst().externalSchedule());assertTrue(fixture.paths.getFirst().contains("%24filter"));}
 @Test void partialScopeEscapeIsQuarantinedNotImported(){fixture.mode="SCOPE_ESCAPE";var p=adapter.fetchPurchaseOrders(request(null));assertTrue(p.items().isEmpty());assertEquals("SAP_ORDER_MAPPING_INVALID",p.issues().getFirst().code());}
 @Test void unitMismatchIsQuarantined(){fixture.mode="BAD_UNIT";assertEquals(1,adapter.fetchPurchaseOrders(request(null)).issues().size());}
 @Test void oversizedSourceKeysAreRejectedBeforeDatabaseImport(){var n=fixture.json.readTree(fixture.json.writeValueAsBytes(fixture.order("PO-1001")));((tools.jackson.databind.node.ObjectNode)n.get("to_PurchaseOrderItem").get("results").get(0)).put("PurchaseOrderItem","X".repeat(41));assertThrows(IllegalArgumentException.class,()->adapter.order(n,SCOPE));var r=fixture.json.readTree(fixture.json.writeValueAsBytes(fixture.receipt(false)));((tools.jackson.databind.node.ObjectNode)r).put("EntryUnit","X".repeat(17));assertThrows(IllegalArgumentException.class,()->adapter.receipt(r,SCOPE));}
 @Test void mutationDuringExpandedReadIsQuarantined(){fixture.mode="UNSTABLE";assertEquals("SAP_ORDER_CHANGED_DURING_READ",adapter.fetchPurchaseOrders(request(null)).issues().getFirst().code());}
 @Test void pagingPreservesScopeAndSnapshot(){fixture.mode="PAGING";var r=request(null);var one=adapter.fetchPurchaseOrders(r);var two=adapter.fetchPurchaseOrders(new Request(r.scenario(),one.nextCursor(),1,0,1,SCOPE,r.snapshotId()));assertEquals("PO-1002",two.items().getFirst().source().externalKey());assertNull(two.nextCursor());assertTrue(fixture.paths.stream().filter(p->p.contains("skiptoken")).allMatch(p->p.contains("%24filter")));}
 @Test void pagingCannotExfiltrateCredentials(){fixture.mode="UNSAFE_NEXT";assertEquals("SAP_UNSAFE_NEXT_LINK",assertThrows(ErpReadFailure.class,()->adapter.fetchPurchaseOrders(request(null))).code());assertEquals(1,fixture.paths.size());}
 @Test void noScopeMeansNoNetworkRequest(){assertEquals("SAP_SCOPE_NOT_CONFIGURED",assertThrows(ErpReadFailure.class,()->adapter.fetchPurchaseOrders(new Request("SAP_READ",null,2,0,1))).code());assertTrue(fixture.paths.isEmpty());}
 @Test void allowedExternalCodesCannotBeRemappedToAnotherLocalOrganization(){var scope=new ReadScope("SAP_TEST","100","COMPANY-A","PURCHASING-A","SUPPLIER-A",List.of("PLANT-1"),"B","A1","S-A",Map.of("PLANT-1","A1"));assertEquals("SAP_MAPPING_NOT_APPROVED",assertThrows(ErpReadFailure.class,()->adapter.fetchPurchaseOrders(new Request("SAP_READ",null,2,0,1,scope,"sap-1"))).code());assertTrue(fixture.paths.isEmpty());}
 @Test void authFailureContainsOnlySafeCode(){fixture.status=401;assertEquals("SAP_AUTHENTICATION_FAILED",assertThrows(ErpReadFailure.class,adapter::verifyMetadata).getMessage());}
 @Test void rateLimitPreservesRetryAfterWithoutLeakingResponse(){fixture.status=429;var e=assertThrows(ErpReadFailure.class,adapter::verifyMetadata);assertEquals("SAP_RATE_LIMITED",e.code());assertEquals(5,e.retryAfterSeconds());}
 @Test void cursorCannotMoveToAnotherBatch(){fixture.mode="PAGING";var r=request(null);var p=adapter.fetchPurchaseOrders(r);int before=fixture.paths.size();assertEquals("SAP_CURSOR_INVALID",assertThrows(ErpReadFailure.class,()->adapter.fetchPurchaseOrders(new Request(r.scenario(),p.nextCursor(),1,0,1,SCOPE,"sap-0"))).code());assertEquals(before,fixture.paths.size());}
 @Test void incompatibleMetadataFailsClosed(){fixture.mode="BAD_METADATA";assertEquals("SAP_METADATA_INCOMPATIBLE",assertThrows(ErpReadFailure.class,adapter::verifyMetadata).code());}
 @Test void cancellationFlagDoesNotDoubleReverseOriginal(){var r=request(null);var before=adapter.fetchGoodsReceipts(r).items().getFirst();fixture.mode="CANCEL_FLAG";assertEquals(before,adapter.fetchGoodsReceipts(r).items().getFirst());assertNull(before.scheduleKey());assertEquals("POSTED",before.eventType());}
 @Test void reversalUsesImmutableOriginalTripleAndEntryUnit(){fixture.mode="REVERSAL";var v=adapter.fetchGoodsReceipts(request(null)).items().getFirst();assertEquals("2026/GR-1/0001",v.originalKey());assertEquals("REVERSAL",v.eventType());assertEquals("EA",v.unit());assertNull(v.scheduleKey());}
 @Test void bothPublishersAlwaysRefuseAndNeverSend(){assertEquals("WRITE_DISABLED",adapter.publishConfirmation(new PublishCommand("X","1"),"key").errorCode());assertEquals("WRITE_DISABLED",adapter.publishAsn(new PublishCommand("X","1"),"key").errorCode());assertTrue(fixture.methods.isEmpty());}
}
