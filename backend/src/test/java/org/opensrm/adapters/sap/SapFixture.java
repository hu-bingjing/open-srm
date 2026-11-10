package org.opensrm.adapters.sap;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.ObjectMapper;

/** Generated TLS identity and wholly virtual ERP documents; no recorded customer data. */
public final class SapFixture implements AutoCloseable {
 public final ObjectMapper json=new ObjectMapper();public final Path directory,configFile;
 public final HttpsServer server;public volatile String mode="INITIAL";public volatile int status=200;
 public final List<String> methods=Collections.synchronizedList(new ArrayList<>());
 public final List<String> paths=Collections.synchronizedList(new ArrayList<>());
 public final java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
 public SapFixture() {
  try {
   directory=Files.createTempDirectory("opensrm-sap-contract-");configFile=directory.resolve("config.json");
   Path passwordFile=directory.resolve("key-password"),keyFile=directory.resolve("server.p12");String password=UUID.randomUUID().toString();Files.writeString(passwordFile,password);
   String tool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
   var process=new ProcessBuilder(tool,"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=ip:127.0.0.1,dns:localhost","-storetype","PKCS12","-keystore",keyFile.toString(),"-storepass:file",passwordFile.toString(),"-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(60,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Test certificate creation failed");
   var ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(keyFile)){ks.load(in,password.toCharArray());}
   Path ca=directory.resolve("test-ca.cer");Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(ks.getCertificate("test").getEncoded())+"\n-----END CERTIFICATE-----\n");
   var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,password.toCharArray());var context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),null,null);
   server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}));server.createContext("/",this::handle);server.start();
   var cfg=new SapReadOnlyAdapter.Config("https://127.0.0.1:"+server.getAddress().getPort(),"100","SAP_TEST","test-reader",UUID.randomUUID().toString(),ca.toString(),List.of("PO-1001","PO-1002"),List.of("COMPANY-A"),List.of("PURCHASING-A"),List.of("SUPPLIER-A"),List.of("PLANT-1"),Map.of(),List.of(new SapReadOnlyAdapter.Mapping("COMPANY-A","PURCHASING-A","SUPPLIER-A","A","A1","S-A",Map.of("PLANT-1","A1"))));
   Files.writeString(configFile,json.writeValueAsString(cfg));
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 public SapReadOnlyAdapter adapter(){return new SapReadOnlyAdapter(configFile.toString(),json);}
 public Map<String,Object> order(String id) {
  boolean update=Set.of("UPDATE","OLD","CONFLICT").contains(mode);String timestamp=mode.equals("OLD")?"2025-12-01T00:00:00Z":update&&!mode.equals("CONFLICT")?"2026-01-02T00:00:00Z":"2026-01-01T00:00:00Z";
  String qty=mode.equals("CONFLICT")?"99":mode.equals("UPDATE")?"90":"100";
  var schedule=new LinkedHashMap<String,Object>(Map.of("PurchasingDocument",id,"PurchasingDocumentItem","00010","ScheduleLine","0001","ScheduleLineOrderQuantity",qty,"ScheduleLineDeliveryDate","/Date(1800000000000)/","PurchaseOrderQuantityUnit","EA"));
  var schedules=new ArrayList<Map<String,Object>>();schedules.add(schedule);
  if(mode.equals("MULTI")) {schedule.put("ScheduleLineOrderQuantity","60");var second=new LinkedHashMap<>(schedule);second.put("ScheduleLine","0002");second.put("ScheduleLineOrderQuantity","40");schedules.add(second);}
  var item=new LinkedHashMap<String,Object>(Map.of("PurchaseOrder",id,"PurchaseOrderItem","00010","Plant","PLANT-1","Material","M-100","PurchaseOrderQuantityUnit","EA","OrderQuantity",qty,"to_ScheduleLine",Map.of("results",schedules)));
  if(mode.equals("BAD_UNIT"))item.put("PurchaseOrderQuantityUnit","KG");
  var head=new LinkedHashMap<String,Object>(Map.of("PurchaseOrder",id,"CompanyCode",mode.equals("SCOPE_ESCAPE")?"COMPANY-B":"COMPANY-A","PurchasingOrganization","PURCHASING-A","Supplier","SUPPLIER-A","LastChangeDateTime",timestamp,"to_PurchaseOrderItem",Map.of("results",List.of(item))));
  if(mode.equals("CANCEL"))head.put("PurchasingDocumentDeletionCode","L");
  return head;
 }
 public Map<String,Object> receipt(boolean reversal) {
  var r=new LinkedHashMap<String,Object>();r.put("MaterialDocumentYear","2026");r.put("MaterialDocument",reversal?"RV-1":"GR-1");r.put("MaterialDocumentItem","0001");r.put("Plant","PLANT-1");r.put("Supplier","SUPPLIER-A");r.put("PurchaseOrder","PO-1001");r.put("PurchaseOrderItem","00010");r.put("GoodsMovementType",reversal?"102":"101");r.put("QuantityInEntryUnit",reversal?"5":"20");r.put("EntryUnit","EA");r.put("GoodsMovementIsCancelled",mode.equals("CANCEL_FLAG"));
  r.put("to_MaterialDocumentHeader",Map.of("MaterialDocumentYear","2026","MaterialDocument",reversal?"RV-1":"GR-1","PostingDate","2026-01-03T00:00:00Z"));
  if(reversal){r.put("ReversedMaterialDocumentYear","2026");r.put("ReversedMaterialDocument","GR-1");r.put("ReversedMaterialDocumentItem","0001");}return r;
 }
 private String metadata(String service) {
  Map<String,List<String>> types=service.contains("PURCHASE")?Map.of("A_PurchaseOrderType",List.of("PurchaseOrder","CompanyCode","PurchasingOrganization","Supplier","LastChangeDateTime"),"A_PurchaseOrderItemType",List.of("PurchaseOrderItem","Plant","Material","OrderQuantity","PurchaseOrderQuantityUnit"),"A_PurchaseOrderScheduleLineType",List.of("PurchasingDocument","PurchasingDocumentItem","ScheduleLine","ScheduleLineOrderQuantity","ScheduleLineDeliveryDate")):Map.of("A_MaterialDocumentHeaderType",List.of("MaterialDocumentYear","MaterialDocument","PostingDate"),"A_MaterialDocumentItemType",List.of("MaterialDocumentItem","PurchaseOrder","PurchaseOrderItem","GoodsMovementType","QuantityInEntryUnit","EntryUnit","ReversedMaterialDocumentYear","ReversedMaterialDocument","ReversedMaterialDocumentItem"));
  var b=new StringBuilder("<Edmx xmlns='urn:test'><DataServices><Schema>");types.forEach((name,fields)->{b.append("<EntityType Name='").append(name).append("'>");fields.forEach(f->b.append("<Property Name='").append(f).append("' Type='Edm.String'/>"));b.append("</EntityType>");});return b.append("</Schema></DataServices></Edmx>").toString();
 }
 private void handle(HttpExchange exchange) throws java.io.IOException {
  try {
   methods.add(exchange.getRequestMethod());paths.add(exchange.getRequestURI().toString());String path=exchange.getRequestURI().getPath(),query=exchange.getRequestURI().getRawQuery();int code=status;Object payload;
   String cfg=Files.readString(configFile);var c=json.readTree(cfg);String auth="Basic "+Base64.getEncoder().encodeToString((c.get("username").asString()+":"+c.get("password").asString()).getBytes(StandardCharsets.UTF_8));
   if(!auth.equals(exchange.getRequestHeaders().getFirst("Authorization")))code=401;
   if(code==429)exchange.getResponseHeaders().set("Retry-After","5");
   if(path.endsWith("$metadata")) {byte[] bytes=(mode.equals("BAD_METADATA")?"<html/>":metadata(path)).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);return;}
   if(path.contains("A_PurchaseOrder(")) {String id=path.contains("PO-1002")?"PO-1002":"PO-1001";var head=order(id);if(mode.equals("UNSTABLE"))head.put("LastChangeDateTime","2026-01-02T00:00:00Z");payload=Map.of("d",head);}
   else if(path.endsWith("A_PurchaseOrder")) {
    var d=new LinkedHashMap<String,Object>();boolean paged=Set.of("PAGING","FAIL_ONCE","UNSAFE_NEXT").contains(mode),second=query!=null&&query.contains("%24skiptoken");
    if(mode.equals("FAIL_ONCE")&&second&&failed.compareAndSet(false,true))code=503;
    d.put("results",List.of(order(second?"PO-1002":"PO-1001")));if(paged&&!second)d.put("__next",mode.equals("UNSAFE_NEXT")?"https://example.invalid/steal":path+"?$skiptoken=page2");payload=Map.of("d",d);
   } else if(path.endsWith("A_MaterialDocumentItem"))payload=Map.of("d",Map.of("results",mode.equals("REVERSAL")?List.of(receipt(true)):List.of(receipt(false))));
   else {code=404;payload=Map.of("error","not found");}
   byte[] bytes=json.writeValueAsBytes(payload);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
  } catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
 }
 public void close(){server.stop(0);}
}
