package org.opensrm.integration.port;
public interface PurchaseOrderProvider {
    String openSnapshot(ErpData.Request request);
    ErpData.Page<ErpData.PurchaseOrder> fetchPurchaseOrders(ErpData.Request request);
}
