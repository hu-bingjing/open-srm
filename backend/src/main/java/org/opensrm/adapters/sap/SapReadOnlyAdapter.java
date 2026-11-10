package org.opensrm.adapters.sap;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import tools.jackson.databind.*;
import org.opensrm.integration.port.*;
import org.opensrm.integration.port.ErpData.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

/** OData V2 GET-only connector. Endpoints and credentials are deployment-private. */
@Component
public class SapReadOnlyAdapter implements ErpAdapter {
 static final String PO="API_PURCHASEORDER_PROCESS_SRV", GR="API_MATERIAL_DOCUMENT_SRV";
 public record Mapping(String company,String purchasingOrg,String supplier,String localCompany,String localPurchasingOrg,String localSupplier,Map<String,String> plants) { }
 public record Config(String baseUrl,String client,String sourceSystem,String username,String password,String caFile,
                      List<String> allowedOrderIds,List<String> allowedCompanies,List<String> allowedPurchasingOrgs,
                      List<String> allowedSuppliers,List<String> allowedPlants,Map<String,String> receiptSchedules,List<Mapping> mappings) {
  @Override public String toString(){return "SapReadOnlyConfig[redacted]";}
 }
 private final Config config; private final HttpClient http;private final ObjectMapper json;
 @Autowired public SapReadOnlyAdapter(@Value("${opensrm.sap.config:}") String file,ObjectMapper json) {
  this.json=json;
  if(file.isBlank()) {config=null;http=null;return;}
  try {
   config=json.readValue(Files.readString(Path.of(file)),Config.class);
   URI uri=URI.create(config.baseUrl());
   if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath())||!config.client().matches("[0-9]{3}")||config.sourceSystem().isBlank()||config.username().isBlank()||config.username().contains(":")||config.password().isBlank()) throw new IllegalArgumentException();
   KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
   try(var in=Files.newInputStream(Path.of(config.caFile()))) {int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in))store.setCertificateEntry("ca-"+(n++),cert);if(n==0)throw new IllegalArgumentException();}
   var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
   var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
   http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  } catch(Exception invalid) {throw new IllegalStateException("Invalid private SAP configuration");}
 }
 // Package-local test seam; production always validates HTTPS and uses the supplied CA.
 SapReadOnlyAdapter(Config config,HttpClient http,ObjectMapper json) {this.config=config;this.http=http;this.json=json;}
 public String type() {return "sap-odata";}
 public boolean liveRead() {return true;}
 public boolean materialsFromOrders() {return true;}
 public String readScenario(String entity) {return switch(entity){case "METADATA"->"SAP_METADATA";case "GOODS_RECEIPT"->"SAP_RECEIPTS";default->"SAP_READ";};}
 public String newReadBatch() {return "sap-"+System.currentTimeMillis();}
 public boolean configured() {return config!=null;}
 private void ready() {if(!configured())throw new ErpReadFailure("SAP_NOT_CONFIGURED");}
 private String root(String service) {return "/sap/opu/odata/sap/"+service+"/";}
 private byte[] get(String path) {
  ready();if(!path.startsWith(root(PO))&&!path.startsWith(root(GR)))throw new ErpReadFailure("SAP_UNSAFE_PATH");
  String auth=Base64.getEncoder().encodeToString((config.username()+":"+config.password()).getBytes(StandardCharsets.UTF_8));
  try {
   var request=HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/$","")+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Basic "+auth).header("Accept","application/json, application/xml").GET().build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
   try(var in=response.body()) {
    int status=response.statusCode();
    if(status==401)throw new ErpReadFailure("SAP_AUTHENTICATION_FAILED");
    if(status==403)throw new ErpReadFailure("SAP_AUTHORIZATION_DENIED");
    if(status==429||status>=500) {
     long delay=0;String value=response.headers().firstValue("Retry-After").orElse("");
     try {delay=Math.max(0,Long.parseLong(value));}catch(Exception ignored){try {delay=Math.max(0,Duration.between(Instant.now(),ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());}catch(Exception invalid){/* use bounded exponential delay */}}
     if(delay>86400)throw new ErpReadFailure("SAP_RETRY_DELAY_EXCESSIVE");
     throw new ErpReadFailure(status==429?"SAP_RATE_LIMITED":"SAP_SERVER_UNAVAILABLE",delay);
    }
    if(status!=200)throw new ErpReadFailure("SAP_HTTP_REJECTED");
    byte[] data=in.readNBytes(4*1024*1024+1);if(data.length>4*1024*1024)throw new ErpReadFailure("SAP_RESPONSE_TOO_LARGE");return data;
   }
  } catch(ErpReadFailure e) {throw e;} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ErpReadFailure("SAP_READ_INTERRUPTED");} catch(Exception e) {throw new ErpReadFailure("SAP_TRANSPORT_FAILED");}
 }
 private String query(Map<String,String> values) {return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElse("");}
 public void verifyMetadata() {
  ready();
  for(String service:List.of(PO,GR)) {
   byte[] data=get(root(service)+"$metadata?sap-client="+config.client());
   try {
    var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
    var doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    if(!"Edmx".equals(doc.getDocumentElement().getLocalName()))throw new IllegalArgumentException();
    var types=doc.getElementsByTagNameNS("*","EntityType");var props=new HashMap<String,Set<String>>();
    for(int i=0;i<types.getLength();i++) {var e=(org.w3c.dom.Element)types.item(i);var fields=new HashSet<String>();var nodes=e.getElementsByTagNameNS("*","Property");for(int j=0;j<nodes.getLength();j++)fields.add(((org.w3c.dom.Element)nodes.item(j)).getAttribute("Name"));props.put(e.getAttribute("Name"),fields);}
    Map<String,Set<String>> expected=service.equals(PO)?Map.of("A_PurchaseOrderType",Set.of("PurchaseOrder","CompanyCode","PurchasingOrganization","Supplier","LastChangeDateTime"),"A_PurchaseOrderItemType",Set.of("PurchaseOrderItem","Plant","Material","OrderQuantity","PurchaseOrderQuantityUnit"),"A_PurchaseOrderScheduleLineType",Set.of("PurchasingDocument","PurchasingDocumentItem","ScheduleLine","ScheduleLineOrderQuantity","ScheduleLineDeliveryDate")):Map.of("A_MaterialDocumentHeaderType",Set.of("MaterialDocumentYear","MaterialDocument","PostingDate"),"A_MaterialDocumentItemType",Set.of("MaterialDocumentItem","PurchaseOrder","PurchaseOrderItem","GoodsMovementType","QuantityInEntryUnit","EntryUnit","ReversedMaterialDocumentYear","ReversedMaterialDocument","ReversedMaterialDocumentItem"));
    for(var entry:expected.entrySet())if(!props.getOrDefault(entry.getKey(),Set.of()).containsAll(entry.getValue()))throw new IllegalArgumentException();
   } catch(Exception invalid) {throw new ErpReadFailure("SAP_METADATA_INCOMPATIBLE");}
  }
 }
 private static String literal(String value) {return "'"+value.replace("'","''")+"'";}
 private static String oneOf(String field,List<String> values) {return "("+values.stream().map(v->field+" eq "+literal(v)).reduce((a,b)->a+" or "+b).orElseThrow()+")";}
 private void scope(Request r) {
  ready();var s=r.scope();
  if(s==null||!config.sourceSystem().equals(s.sourceSystem())||!config.client().equals(s.client())||config.allowedOrderIds()==null||config.allowedOrderIds().isEmpty()||config.allowedOrderIds().size()>20||config.allowedOrderIds().stream().anyMatch(v->!v.matches("[A-Za-z0-9-]{1,40}"))||!allowed(config.allowedCompanies(),s.company())||!allowed(config.allowedPurchasingOrgs(),s.purchasingOrg())||!allowed(config.allowedSuppliers(),s.supplier())||s.plants().isEmpty()||s.plants().stream().anyMatch(p->!allowed(config.allowedPlants(),p)))throw new ErpReadFailure("SAP_SCOPE_NOT_CONFIGURED");
  if(config.mappings()==null||s.localPlants()==null||!s.localPlants().keySet().equals(new HashSet<>(s.plants()))||config.mappings().stream().noneMatch(m->Objects.equals(m.company(),s.company())&&Objects.equals(m.purchasingOrg(),s.purchasingOrg())&&Objects.equals(m.supplier(),s.supplier())&&Objects.equals(m.localCompany(),s.localCompany())&&Objects.equals(m.localPurchasingOrg(),s.localPurchasingOrg())&&Objects.equals(m.localSupplier(),s.localSupplier())&&m.plants()!=null&&m.plants().entrySet().containsAll(s.localPlants().entrySet())))throw new ErpReadFailure("SAP_MAPPING_NOT_APPROVED");
 }
 private boolean allowed(List<String> values,String key) {return values!=null&&!key.equals("*")&&values.contains(key);}
 public String openSnapshot(Request r) {scope(r);return "sap-"+Instant.now().toEpochMilli();}
 private String snapshot(Request r) {return r.snapshotId()==null?"sap-receipts":r.snapshotId();}
 private JsonNode read(String path) {try {return json.readTree(get(path));}catch(ErpReadFailure e){throw e;}catch(Exception e){throw new ErpReadFailure("SAP_RESPONSE_INVALID");}}
 private record Rows(List<JsonNode> values,String cursor) { }
 private Rows rows(Request r,String service,String entity,String filter,String expand,String orderBy) {
  scope(r);if(r.pageSize()<1||r.pageSize()>100)throw new ErpReadFailure("SAP_PAGE_INVALID");
  var q=new LinkedHashMap<String,String>();q.put("sap-client",config.client());q.put("$format","json");q.put("$filter",filter);q.put("$top",Integer.toString(Math.min(20,r.pageSize())));q.put("$orderby",orderBy);if(expand!=null)q.put("$expand",expand);
  String base=root(service)+entity,path=base+"?"+query(q);
  if(r.cursor()!=null) {
   try {var token=json.readTree(Base64.getUrlDecoder().decode(r.cursor()));if(!snapshot(r).equals(token.get("snapshot").asString())||!filter.equals(token.get("filter").asString()))throw new IllegalArgumentException();path=nextPath(token.get("next").asString(),base,q);}
   catch(Exception invalid) {throw new ErpReadFailure("SAP_CURSOR_INVALID");}
  }
  var body=read(path).get("d");if(body==null||!body.has("results")||!body.get("results").isArray())throw new ErpReadFailure("SAP_RESPONSE_INVALID");
  var values=new ArrayList<JsonNode>();body.get("results").forEach(values::add);if(values.size()>100)throw new ErpReadFailure("SAP_RESPONSE_TOO_LARGE");
  String cursor=null;
  if(body.has("__next")) {
   String next=nextPath(body.get("__next").asString(),base,q);
   if(next.equals(path)||values.isEmpty())throw new ErpReadFailure("SAP_PAGINATION_STALLED");
   cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("snapshot",snapshot(r),"filter",filter,"next",next)));
   if(cursor.length()>4000)throw new ErpReadFailure("SAP_CURSOR_TOO_LARGE");
  }
  return new Rows(values,cursor);
 }
 private String nextPath(String next,String base,Map<String,String> required) {
  try {
   URI origin=URI.create(config.baseUrl()),u=URI.create(config.baseUrl().replaceAll("/$","")+base).resolve(next);
   if(!Objects.equals(origin.getScheme(),u.getScheme())||!Objects.equals(origin.getHost(),u.getHost())||origin.getPort()!=u.getPort()||u.getUserInfo()!=null||u.getFragment()!=null||!u.getPath().equals(base))throw new IllegalArgumentException();
   var q=new LinkedHashMap<String,String>();for(String pair:u.getRawQuery().split("&")) {var kv=pair.split("=",2);String k=URLDecoder.decode(kv[0],StandardCharsets.UTF_8);if(q.putIfAbsent(k,URLDecoder.decode(kv.length==2?kv[1]:"",StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();}
   for(var e:required.entrySet())if(q.containsKey(e.getKey())&&!q.get(e.getKey()).equals(e.getValue()))throw new IllegalArgumentException();
   if(q.keySet().stream().anyMatch(k->!required.containsKey(k)&&!Set.of("$skip","$skiptoken").contains(k)))throw new IllegalArgumentException();
   q.putAll(required);return base+"?"+query(q);
  }catch(Exception e){throw new ErpReadFailure("SAP_UNSAFE_NEXT_LINK");}
 }
 static String text(JsonNode n,String key) {var v=n==null?null:n.get(key);return v==null||v.isNull()?"":v.asString();}
 private static String required(JsonNode n,String key) {String v=text(n,key);if(v.isBlank()||v.length()>80)throw new IllegalArgumentException();return v;}
 static Instant instant(String value) {
  if(value.matches("/Date\\(-?[0-9]+([+-][0-9]{4})?\\)/"))return Instant.ofEpochMilli(Long.parseLong(value.substring(6,value.length()-2).replaceAll("([+-][0-9]{4})$","")));
  return Instant.parse(value);
 }
 private static java.math.BigDecimal quantity(JsonNode n,String key) {var v=new java.math.BigDecimal(required(n,key)).stripTrailingZeros();if(v.signum()<=0||v.scale()>6||v.precision()-v.scale()>13)throw new IllegalArgumentException();return v;}
 private static List<JsonNode> expanded(JsonNode node,String key) {
  var n=node.get(key);if(n==null||n.has("__next")||n.has("__deferred"))throw new IllegalArgumentException();
  var array=n.get("results");if(array==null||!array.isArray())throw new IllegalArgumentException();var out=new ArrayList<JsonNode>();array.forEach(out::add);return out;
 }
 PurchaseOrder order(JsonNode n,ReadScope scope) {
  String id=required(n,"PurchaseOrder");if(!scope.company().equals(text(n,"CompanyCode"))||!scope.purchasingOrg().equals(text(n,"PurchasingOrganization"))||!scope.supplier().equals(text(n,"Supplier"))||!config.allowedOrderIds().contains(id))throw new IllegalArgumentException();
  Instant changed=instant(required(n,"LastChangeDateTime"));long sequence=changed.toEpochMilli();if(sequence<1)throw new IllegalArgumentException();
  String deletion=text(n,"PurchasingDocumentDeletionCode");if(!Set.of("","L").contains(deletion))throw new IllegalArgumentException();
  var rawItems=expanded(n,"to_PurchaseOrderItem");var items=new ArrayList<Item>();boolean allClosed=!rawItems.isEmpty(),allDeleted=!rawItems.isEmpty();
  for(var i:rawItems) {String deleted=text(i,"PurchasingDocumentDeletionCode");if(!Set.of("","L").contains(deleted)||"true".equals(text(i,"IsReturnsItem")))throw new IllegalArgumentException();allDeleted&=deleted.equals("L");allClosed&="true".equals(text(i,"IsCompletelyDelivered"));}
  for(var i:rawItems) {
   if(!deletion.equals("L")&&!allDeleted&&text(i,"PurchasingDocumentDeletionCode").equals("L"))continue;
   String item=required(i,"PurchaseOrderItem"),plant=required(i,"Plant"),unit=required(i,"PurchaseOrderQuantityUnit"),material=required(i,"Material");
   if(item.length()>40||unit.length()>16||!id.equals(text(i,"PurchaseOrder"))||!scope.plants().contains(plant))throw new IllegalArgumentException();
   var schedules=new ArrayList<Schedule>();var total=java.math.BigDecimal.ZERO;
   for(var s:expanded(i,"to_ScheduleLine")) {
    if(!id.equals(text(s,"PurchasingDocument"))||!item.equals(text(s,"PurchasingDocumentItem"))||!unit.equals(text(s,"PurchaseOrderQuantityUnit")))throw new IllegalArgumentException();
    String schedule=required(s,"ScheduleLine");if(schedule.length()>40)throw new IllegalArgumentException();var q=quantity(s,"ScheduleLineOrderQuantity");total=total.add(q);schedules.add(new Schedule(schedule,q,instant(required(s,"ScheduleLineDeliveryDate")).atZone(ZoneOffset.UTC).toLocalDate()));
   }
   if(schedules.isEmpty()||total.compareTo(quantity(i,"OrderQuantity"))!=0)throw new IllegalArgumentException();
   schedules.sort(Comparator.comparing(Schedule::externalSchedule));items.add(new Item(item,plant,material,unit,List.copyOf(schedules)));
  }
  if(items.isEmpty())throw new IllegalArgumentException();items.sort(Comparator.comparing(Item::externalItem));
  return new PurchaseOrder(new Source(config.sourceSystem(),config.client(),id,"sap-ts-"+sequence,sequence,changed),scope.company(),scope.purchasingOrg(),scope.supplier(),deletion.equals("L")||allDeleted?"CANCELLED":allClosed?"CLOSED":"ACTIVE",List.copyOf(items));
 }
 public Page<PurchaseOrder> fetchPurchaseOrders(Request r) {
  scope(r);var s=r.scope();String filter="CompanyCode eq "+literal(s.company())+" and PurchasingOrganization eq "+literal(s.purchasingOrg())+" and Supplier eq "+literal(s.supplier())+" and "+oneOf("PurchaseOrder",config.allowedOrderIds());
  var data=rows(r,PO,"A_PurchaseOrder",filter,"to_PurchaseOrderItem/to_ScheduleLine","PurchaseOrder");var values=new ArrayList<PurchaseOrder>();var issues=new ArrayList<ReadIssue>();
  for(var node:data.values()) {try {var v=order(node,s);if(v.source().sourceSequence()>Long.parseLong(snapshot(r).substring(4)))throw new IllegalArgumentException();
    // A second, scoped read detects header changes while expanded children were obtained.
    var current=read(root(PO)+"A_PurchaseOrder("+literal(v.source().externalKey())+")?"+query(Map.of("sap-client",config.client(),"$format","json","$select","PurchaseOrder,LastChangeDateTime"))).get("d");
    if(!v.source().sourceUpdatedAt().equals(instant(required(current,"LastChangeDateTime")))) {issues.add(new ReadIssue(v.source().externalKey(),v.source().sourceRevision(),"SAP_ORDER_CHANGED_DURING_READ"));continue;}values.add(v);
   } catch(ErpReadFailure e){throw e;}catch(Exception invalid){issues.add(new ReadIssue(safeKey(node,"PurchaseOrder"),"unmapped","SAP_ORDER_MAPPING_INVALID"));}}
  return new Page<>(values,data.cursor(),snapshot(r),issues);
 }
 private static String safeKey(JsonNode node,String key) {String value=text(node,key);return value.isBlank()?"unmapped":value.substring(0,Math.min(70,value.length()));}
 GoodsReceipt receipt(JsonNode n,ReadScope scope) {
  String order=required(n,"PurchaseOrder"),plant=required(n,"Plant"),supplier=text(n,"Supplier");if(!config.allowedOrderIds().contains(order)||!scope.plants().contains(plant)||(!supplier.isBlank()&&!supplier.equals(scope.supplier())))throw new IllegalArgumentException();
  String key=required(n,"MaterialDocumentYear")+"/"+required(n,"MaterialDocument")+"/"+required(n,"MaterialDocumentItem");String move=required(n,"GoodsMovementType");if(!Set.of("101","102").contains(move))throw new IllegalArgumentException();
  String original=null;if(move.equals("102"))original=required(n,"ReversedMaterialDocumentYear")+"/"+required(n,"ReversedMaterialDocument")+"/"+required(n,"ReversedMaterialDocumentItem");
  if(key.length()>160||(original!=null&&original.length()>160)||required(n,"EntryUnit").length()>16||required(n,"PurchaseOrderItem").length()>40)throw new IllegalArgumentException();
  var header=n.get("to_MaterialDocumentHeader");if(header==null||header.has("__deferred")||!text(n,"MaterialDocument").equals(text(header,"MaterialDocument"))||!text(n,"MaterialDocumentYear").equals(text(header,"MaterialDocumentYear")))throw new IllegalArgumentException();
  Instant posting=instant(required(header,"PostingDate"));String schedule=config.receiptSchedules()==null?null:config.receiptSchedules().get(key);
  // Cancellation flag on the original is mutable. Only the referenced reversal document posts a reversal.
  return new GoodsReceipt(new Source(config.sourceSystem(),config.client(),key,"sap-document",1,posting),order,required(n,"PurchaseOrderItem"),schedule,quantity(n,"QuantityInEntryUnit"),posting.atZone(ZoneOffset.UTC).toLocalDate(),required(n,"EntryUnit"),move.equals("101")?"POSTED":"REVERSAL",original,List.of());
 }
 public Page<GoodsReceipt> fetchGoodsReceipts(Request r) {
  scope(r);String filter=oneOf("PurchaseOrder",config.allowedOrderIds())+" and "+oneOf("Plant",r.scope().plants())+" and (GoodsMovementType eq '101' or GoodsMovementType eq '102')";
  var data=rows(r,GR,"A_MaterialDocumentItem",filter,"to_MaterialDocumentHeader","MaterialDocumentYear,MaterialDocument,MaterialDocumentItem");var values=new ArrayList<GoodsReceipt>();var issues=new ArrayList<ReadIssue>();
  for(var node:data.values())try {values.add(receipt(node,r.scope()));}catch(Exception invalid){issues.add(new ReadIssue(safeKey(node,"MaterialDocument"),"unmapped","SAP_RECEIPT_MAPPING_INVALID"));}
  return new Page<>(values,data.cursor(),snapshot(r),issues);
 }
 public Page<Material> fetchMaterials(Request r) {return new Page<>(List.of(),null,snapshot(r));}
 public Page<Organization> fetchOrganizations(Request r) {throw new ErpReadFailure("SAP_CAPABILITY_UNSUPPORTED");}
 public Page<Supplier> fetchSuppliers(Request r) {throw new ErpReadFailure("SAP_CAPABILITY_UNSUPPORTED");}
 public PublishResult publishConfirmation(PublishCommand c,String key) {return new PublishResult("UNSUPPORTED",null,"WRITE_DISABLED");}
 public PublishResult publishAsn(PublishCommand c,String key) {return new PublishResult("UNSUPPORTED",null,"WRITE_DISABLED");}
}
