package org.opensrm.integration.port;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
public final class ErpData {
 private ErpData() { }
 public record ReadScope(String sourceSystem,String client,String company,String purchasingOrg,String supplier,List<String> plants,String localCompany,String localPurchasingOrg,String localSupplier,Map<String,String> localPlants) {
  public ReadScope(String sourceSystem,String client,String company,String purchasingOrg,String supplier,List<String> plants) {this(sourceSystem,client,company,purchasingOrg,supplier,plants,null,null,null,Map.of());}
 }
 public record Request(String scenario,String cursor,int pageSize,int attempt,int plantCount,ReadScope scope,String snapshotId) {
  public Request(String scenario,String cursor,int pageSize,int attempt,int plantCount) {this(scenario,cursor,pageSize,attempt,plantCount,null,null);}
 }
 public record ReadIssue(String externalKey,String sourceRevision,String code) { }
 public record Page<T>(List<T> items,String nextCursor,String snapshotId,List<ReadIssue> issues) {
  public Page(List<T> items,String nextCursor,String snapshotId) {this(items,nextCursor,snapshotId,List.of());}
 }
 public record Source(String sourceSystem,String sourceClient,String externalKey,String sourceRevision,long sourceSequence,Instant sourceUpdatedAt) { }
 public record Organization(Source source,String kind,String name,String parentKey) { }
 public record Supplier(Source source,String name,List<String> companyKeys) { }
 public record Material(Source source,String name,String unit) { }
 public record Schedule(String externalSchedule,BigDecimal orderedQuantity,LocalDate requestedDate) { }
 public record Item(String externalItem,String plantKey,String materialKey,String unit,List<Schedule> schedules) { }
 public record PurchaseOrder(Source source,String companyKey,String purchasingOrgKey,String supplierKey,String lifecycle,List<Item> items) { }
 public record ReceiptAllocation(String asnReference,int lineNumber,BigDecimal quantity) { }
 public record GoodsReceipt(Source source,String orderKey,String itemKey,String scheduleKey,BigDecimal quantity,LocalDate postingDate,String unit,String eventType,String originalKey,List<ReceiptAllocation> allocations) { }
 public record PublishCommand(String externalKey,String sourceRevision) { }
 public record PublishResult(String outcome,String externalReference,String errorCode) { }
}
