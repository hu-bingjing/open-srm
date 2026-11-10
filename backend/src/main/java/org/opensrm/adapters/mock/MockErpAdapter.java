package org.opensrm.adapters.mock;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.opensrm.integration.port.*;
import org.opensrm.integration.port.ErpData.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
public class MockErpAdapter implements ErpAdapter {
    public String type() {return "mock";}
    private Source source(String key,int revision) { return new Source("MOCK","DEMO",key,"mock-r"+revision,revision,Instant.parse("2026-01-01T00:00:00Z").plusSeconds(revision)); }
    private <T> Page<T> page(List<T> values,Request r,String snapshot) {
        if(r.pageSize()<1 || r.pageSize()>100) throw new IllegalArgumentException("Invalid page size");
        int offset=r.cursor()==null?0:Integer.parseInt(new String(Base64.getUrlDecoder().decode(r.cursor()),java.nio.charset.StandardCharsets.UTF_8));
        if(offset<0 || offset>values.size()) throw new IllegalArgumentException("Invalid mock cursor");
        int end=Math.min(values.size(),offset+r.pageSize());String next=end==values.size()?null:Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Page<>(List.copyOf(values.subList(offset,end)),next,snapshot);
    }
    public Page<Organization> fetchOrganizations(Request r) { return page(List.of(new Organization(source("COMPANY-A",1),"COMPANY","Company A",null),new Organization(source("COMPANY-B",1),"COMPANY","Company B",null),new Organization(source("PLANT-1",1),"PLANT","Plant A","COMPANY-A"),new Organization(source("PLANT-2",1),"PLANT","Plant B","COMPANY-B"),new Organization(source("PURCHASING-A",1),"PURCHASING_ORGANIZATION","Purchasing A",null)),r,"mock-organizations-1"); }
    public Page<Supplier> fetchSuppliers(Request r) { return page(List.of(new Supplier(source("SUPPLIER-A",1),"Supplier A",List.of("COMPANY-A","COMPANY-B")),new Supplier(source("SUPPLIER-B",1),"Supplier B",List.of("COMPANY-B"))),r,"mock-suppliers-1"); }
    public Page<Material> fetchMaterials(Request r) { return page(List.of(new Material(source("M-100",1),"Material A","EA")),r,"mock-materials-1"); }
    private GoodsReceipt receipt(String key,String qty,String type,String original,List<ReceiptAllocation> allocations) {
        return new GoodsReceipt(source("2027/"+key+"/0001",1),"PO-1001","00010","0001",new BigDecimal(qty),LocalDate.of(2027,1,20),"EA",type,original,allocations);
    }
    private ReceiptAllocation allocation(String ref,String qty) {return new ReceiptAllocation(ref,1,new BigDecimal(qty));}
    public Page<GoodsReceipt> fetchGoodsReceipts(Request r) {
        if(r.cursor()!=null && r.scenario().equals("GR_FAIL_ONCE") && r.attempt()==0) throw new ErpReadFailure();
        List<GoodsReceipt> values=switch(r.scenario()) {
         case "GR_INITIAL","GR_FAIL_ONCE" -> List.of(receipt("GR-A","20","POSTED",null,List.of(allocation("ASN-A","10"),allocation("ASN-B","10"))),receipt("GR-B","5","POSTED",null,List.of()),receipt("GR-C","5","POSTED",null,List.of()));
         case "GR_PARTIAL_REVERSE" -> List.of(receipt("RV-A","5","REVERSAL","2027/GR-A/0001",List.of(allocation("ASN-A","5"))));
         case "GR_FULL_REVERSE" -> List.of(receipt("RV-B","15","REVERSAL","2027/GR-A/0001",List.of(allocation("ASN-A","5"),allocation("ASN-B","10"))));
         case "GR_CONFLICT" -> List.of(receipt("GR-A","21","POSTED",null,List.of()));
         case "GR_OVER" -> List.of(receipt("GR-OVER","200","POSTED",null,List.of()));
         case "GR_UNMATCHED" -> List.of(new GoodsReceipt(source("2027/UNKNOWN/0001",1),"UNKNOWN","00010","0001",BigDecimal.ONE,LocalDate.of(2027,1,20),"EA","POSTED",null,List.of()));
         default -> List.of();
        };return page(values,r,"mock-receipts-"+r.scenario()+"-1");
    }
    public PublishResult publishConfirmation(PublishCommand c,String key) { return new PublishResult("UNSUPPORTED",null,"WRITE_DISABLED"); }
    public PublishResult publishAsn(PublishCommand c,String key) { return new PublishResult("UNSUPPORTED",null,"WRITE_DISABLED"); }
    private Item item(String key,String plant,String q,boolean split,int revision) {
        var schedules=new ArrayList<Schedule>();schedules.add(new Schedule("0001",new BigDecimal(q),LocalDate.of(2027,1,15).plusDays(revision)));
        if(split) schedules.add(new Schedule("0002",new BigDecimal("40.000000"),LocalDate.of(2027,2,15).plusDays(revision)));
        return new Item(key,plant,"M-100","EA",List.copyOf(schedules));
    }
    public String openSnapshot(Request r) { return "mock-orders-"+r.scenario()+"-1"; }
    public Page<PurchaseOrder> fetchPurchaseOrders(Request r) {
        if(r.pageSize()<1 || r.pageSize()>100) throw new IllegalArgumentException("Invalid page size");
        int offset=r.cursor()==null?0:Integer.parseInt(new String(Base64.getUrlDecoder().decode(r.cursor()),java.nio.charset.StandardCharsets.UTF_8));
        if(offset>=2 && (r.scenario().equals("PERMANENT_FAIL") || (r.scenario().equals("FAIL_ONCE") && r.attempt()==0))) throw new ErpReadFailure();
        int revision=switch(r.scenario()) { case "UPDATE","REMOVE_LINE" -> 2;case "CANCEL","CLOSE" -> 3;case "REOPEN" -> 4;default -> 1; };
        var records=new ArrayList<PurchaseOrder>();
        for(int n=1;n<=4;n++) {
            var items=new ArrayList<Item>();String plant=n==2&&r.plantCount()>1?"PLANT-2":"PLANT-1";
            if(n==1&&r.scenario().equals("INVALID_MAPPING")) plant="UNMAPPED";
            items.add(item("00010",plant,n==1&&r.scenario().equals("CONFLICT")?"999":n==1&&r.scenario().equals("CONFIRMATION")?"100":revision==1?"60":"55",true,revision));
            if(n==1 && r.plantCount()>1 && !r.scenario().equals("REMOVE_LINE")) items.add(item("00020","PLANT-2","25",false,revision));
            Source src=source("PO-100"+n,revision);
            if(r.scenario().equals("OUT_OF_ORDER")) src=new Source("MOCK","DEMO",src.externalKey(),"unseen-old",1,src.sourceUpdatedAt());
            String state=r.scenario().equals("CANCEL")?"CANCELLED":r.scenario().equals("CLOSE")?"CLOSED":"ACTIVE";
            records.add(new PurchaseOrder(src,"COMPANY-A","PURCHASING-A","SUPPLIER-A",state,List.copyOf(items)));
        }
        if(r.scenario().equals("DUPLICATE")) records.addAll(List.copyOf(records));
        if(offset<0 || offset>records.size()) throw new IllegalArgumentException("Invalid mock cursor");
        int end=Math.min(records.size(),offset+r.pageSize());
        String next=end==records.size()?null:Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Page<>(List.copyOf(records.subList(offset,end)),next,openSnapshot(r));
    }
}
