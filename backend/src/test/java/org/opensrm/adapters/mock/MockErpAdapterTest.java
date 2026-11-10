package org.opensrm.adapters.mock;
import org.junit.jupiter.api.Test;
import org.opensrm.integration.port.*;
import org.opensrm.integration.port.ErpData.*;
import static org.junit.jupiter.api.Assertions.*;
class MockErpAdapterTest {
 @Test void snapshotAndCursorAreDeterministic() {
  PurchaseOrderProvider p=new MockErpAdapter();var r=new Request("INITIAL",null,2,0,2);var first=p.fetchPurchaseOrders(r);var second=p.fetchPurchaseOrders(new Request("INITIAL",first.nextCursor(),2,0,2));
  assertEquals(p.openSnapshot(r),first.snapshotId());assertEquals(first.snapshotId(),second.snapshotId());assertEquals(2,first.items().size());assertEquals(2,second.items().size());assertNull(second.nextCursor());assertNotEquals(first.items().getFirst().source().externalKey(),second.items().getFirst().source().externalKey());
 }
 @Test void retryReusesSnapshotAndCheckpoint() {
  var p=new MockErpAdapter();var first=p.fetchPurchaseOrders(new Request("FAIL_ONCE",null,2,0,2));
  assertThrows(ErpReadFailure.class,()->p.fetchPurchaseOrders(new Request("FAIL_ONCE",first.nextCursor(),2,0,2)));
  var page=p.fetchPurchaseOrders(new Request("FAIL_ONCE",first.nextCursor(),2,1,2));assertEquals(first.snapshotId(),page.snapshotId());assertEquals("PO-1003",page.items().getFirst().source().externalKey());
 }
 @Test void disabledPublishersCannotWriteAnything() {
  var p=new MockErpAdapter();var c=new PublishCommand("synthetic-command","opaque-revision");assertEquals("UNSUPPORTED",p.publishConfirmation(c,"synthetic-key").outcome());assertEquals("UNSUPPORTED",p.publishAsn(c,"synthetic-key").outcome());assertTrue(p.fetchGoodsReceipts(new Request("INITIAL",null,2,0,2)).items().isEmpty());
 }
}
