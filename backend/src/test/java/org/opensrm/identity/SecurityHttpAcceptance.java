package org.opensrm.identity;
import java.util.*;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
abstract class SecurityHttpAcceptance extends WorkspaceHttpAcceptance {
 HttpResponse<String> originWrite(String origin,String site,boolean token)throws Exception {
  var me=get(noBusiness,"/auth/me");var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/profile")).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).header("If-Match","\""+me.get("version").asLong()+"\"");
  if(origin!=null)builder.header("Origin",origin);if(site!=null)builder.header("Sec-Fetch-Site",site);if(token)builder.header("X-CSRF-Token",get(noBusiness,"/auth/csrf").get("csrfToken").asString());
  return noBusiness.send(builder.method("PATCH",HttpRequest.BodyPublishers.ofString("{\"displayName\":\"Synthetic origin test\"}")).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test @Order(150) void crossOriginAndCrossSiteWritesFailEvenWithValidCsrf()throws Exception {
  String before=get(noBusiness,"/auth/me").toString();long audit=count("audit_event"),idem=count("idempotency_record");
  for(String origin:List.of("https://attacker.example.invalid","null","http://127.0.0.1:"+port+"/path","https://127.0.0.1:"+port))assertEquals(403,originWrite(origin,null,true).statusCode());
  assertEquals(403,originWrite("http://127.0.0.1:"+port,"cross-site",true).statusCode());
  assertEquals(before,get(noBusiness,"/auth/me").toString());assertEquals(audit,count("audit_event"));assertEquals(idem,count("idempotency_record"));
 }
 @Test @Order(151) void sameOriginStillNeedsCsrfAndNormalAuthorizedCommandWorks()throws Exception {
  assertEquals(403,originWrite("http://127.0.0.1:"+port,"same-origin",false).statusCode());
  assertEquals(200,originWrite("http://127.0.0.1:"+port,"same-origin",true).statusCode());
 }
 @Test @Order(152) void preflightCannotEnableCorsOrExposeOperationalEndpoints()throws Exception {
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/profile")).header("Origin","https://attacker.example.invalid").header("Access-Control-Request-Method","PATCH").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
  var response=noBusiness.send(request,HttpResponse.BodyHandlers.ofString());assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  for(String path:List.of("/actuator/env","/actuator/configprops","/api/v1/../private/idempotency-key"))assertTrue(call(client(),"GET",path,null).statusCode()>=400);
  var me=call(noBusiness,"GET","/auth/me",null);assertEquals("nosniff",me.headers().firstValue("X-Content-Type-Options").orElse(""));assertEquals("DENY",me.headers().firstValue("X-Frame-Options").orElse(""));assertTrue(me.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
 }
}
