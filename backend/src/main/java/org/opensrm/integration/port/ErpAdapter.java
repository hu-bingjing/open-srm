package org.opensrm.integration.port;
/** Vendor-independent adapter registration; publishers must remain disabled. */
public interface ErpAdapter extends OrganizationProvider,SupplierProvider,MaterialProvider,PurchaseOrderProvider,GoodsReceiptProvider,OrderConfirmationPublisher,AdvanceShippingNoticePublisher {
 String type();
 default boolean configured() { return true; }
 default boolean liveRead() {return false;}
 default boolean materialsFromOrders() {return false;}
 default String readScenario(String entity) {return null;}
 default String newReadBatch() {return java.util.UUID.randomUUID().toString();}
 default void verifyMetadata() { throw new ErpReadFailure("METADATA_UNSUPPORTED"); }
}
