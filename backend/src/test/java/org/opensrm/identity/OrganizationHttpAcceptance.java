package org.opensrm.identity;

import java.net.*;
import java.net.http.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class OrganizationHttpAcceptance extends IdentityHttpAcceptance {
    String companyA,companyB,plantA,plantA2,plantB,orgA,orgA2,supplierA,supplierB,adminId,buyerId,externalId,buyerGrant;
    String sharedKey="shared-update-0001",sharedEtag;
    final String buyerPassword=UUID.randomUUID().toString(),externalPassword=UUID.randomUUID().toString();
    final HttpClient buyer=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    final HttpClient external=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    JsonNode ok(HttpResponse<String> r,int code) { assertEquals(code,r.statusCode(),r.body());return json.readTree(r.body()); }
    JsonNode get(HttpClient c,String path) throws Exception { return ok(call(c,"GET",path,null),200); }
    String id(JsonNode n) { return n.get("id").asString(); }
    String etag(HttpClient c,String path) throws Exception { return "\""+get(c,path).get("version").asLong()+"\""; }
    JsonNode write(HttpClient c,String method,String path,Object body,int expected) throws Exception { return ok(send(c,method,path,body,true,UUID.randomUUID().toString(),etag(c,path)),expected); }
    void grant(String user,String permission,String company,String plant,String org) throws Exception {
        var body=new HashMap<String,Object>();body.put("permission",permission);body.put("companyId",company);body.put("plantId",plant);body.put("purchasingOrgId",org);
        ok(send(admin,"POST","/users/"+user+"/grants",body,true,UUID.randomUUID().toString(),etag(admin,"/users/"+user)),201);
    }
    String company(String code) throws Exception { return id(ok(call(admin,"POST","/companies",Map.of("code",code,"name","Company "+code,"timezone","Asia/Shanghai","active",true)),201)); }
    String plant(String code,String company) throws Exception { return id(ok(call(admin,"POST","/plants",Map.of("code",code,"name","Plant "+code,"companyId",company,"active",true)),201)); }
    String purchasing(String code,List<String> companies) throws Exception { return id(ok(call(admin,"POST","/purchasing-organizations",Map.of("code",code,"name","Purchasing "+code,"companyIds",companies,"active",true)),201)); }
    @Test @Order(20) void migrationAndCatalogCreationPreserveIdentity() throws Exception {
        ok(call(admin,"POST","/auth/login",Map.of("login","admin-a","password",adminPassword)),200);adminId=id(get(admin,"/auth/me"));
        assertEquals(8,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));
        companyA=company("A");companyB=company("B");plantA=plant("A1",companyA);plantA2=plant("A2",companyA);plantB=plant("B1",companyB);
        orgA=purchasing("A1",List.of(companyA,companyB));orgA2=purchasing("A2",List.of(companyA));
        assertEquals(2,get(admin,"/companies").get("total").asInt());
        assertEquals(2,get(admin,"/purchasing-organizations/"+orgA).get("companyIds").size());
        assertEquals(0,get(admin,"/suppliers").get("total").asInt());
        assertEquals(404,call(admin,"POST","/suppliers",Map.of("code","UNSCOPED","name","Supplier U","companyIds",List.of(companyA))).statusCode());
    }
    @Test @Order(21) void scopeAdministrationAndRoleAreBothNecessary() throws Exception {
        buyerId=id(ok(call(admin,"POST","/users",Map.of("login","buyer-a","displayName","Buyer A","password",buyerPassword,"roles",List.of("BUYER"))),201));
        externalId=id(ok(call(admin,"POST","/users",Map.of("login","supplier-a","displayName","Supplier User A","password",externalPassword,"roles",List.of("SUPPLIER"))),201));
        ok(call(buyer,"POST","/auth/login",Map.of("login","buyer-a","password",buyerPassword)),200);
        assertEquals(0,get(buyer,"/companies").get("total").asInt());assertEquals(404,call(buyer,"GET","/companies/"+companyA,null).statusCode());
        assertEquals(403,call(buyer,"GET","/users/"+buyerId+"/grants",null).statusCode());
        grant(adminId,"supplier.manage",companyA,null,null);grant(adminId,"supplier.manage",companyB,null,null);
        grant(buyerId,"supplier.manage",companyA,null,null);
        buyerGrant=id(get(admin,"/users/"+buyerId+"/grants").get(0));
        assertEquals(1,get(buyer,"/companies").get("total").asInt());
        assertEquals(404,call(buyer,"GET","/companies/"+companyB,null).statusCode());
        assertEquals(403,call(buyer,"POST","/companies",Map.of("code","X","name","Company X","timezone","UTC","active",true)).statusCode());
    }
    @Test @Order(22) void supplierBatchScopeFailureRollsBackCompletely() throws Exception {
        long audit=db.queryForObject("SELECT count(*) FROM audit_event",Long.class),idem=db.queryForObject("SELECT count(*) FROM idempotency_record",Long.class);
        assertEquals(404,call(buyer,"POST","/suppliers",Map.of("code","BAD","name","Supplier Bad","companyIds",List.of(companyA,companyB))).statusCode());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM supplier WHERE code='BAD'",Integer.class));
        assertEquals(audit,db.queryForObject("SELECT count(*) FROM audit_event",Long.class));assertEquals(idem,db.queryForObject("SELECT count(*) FROM idempotency_record",Long.class));
        supplierA=id(ok(call(admin,"POST","/suppliers",Map.of("code","S-A","name","Supplier A","companyIds",List.of(companyA,companyB))),201));
        supplierB=id(ok(call(admin,"POST","/suppliers",Map.of("code","S-B","name","Supplier B","companyIds",List.of(companyB))),201));
    }
    @Test @Order(23) void supplierListsDetailsCountsAndLinksAreScoped() throws Exception {
        var v=get(buyer,"/suppliers?size=1&query=Supplier");assertEquals(1,v.get("total").asInt());assertEquals(1,v.get("items").size());
        var s=get(buyer,"/suppliers/"+supplierA);assertEquals(1,s.get("companies").size());assertEquals(companyA,s.get("companies").get(0).get("companyId").asString());assertFalse(s.get("canEdit").asBoolean());
        assertEquals(404,call(buyer,"GET","/suppliers/"+supplierB,null).statusCode());assertEquals(0,get(buyer,"/suppliers?companyId="+companyB).get("total").asInt());
        assertEquals(404,send(buyer,"PATCH","/suppliers/"+supplierA,Map.of("name","Unauthorized","status","FROZEN"),true,UUID.randomUUID().toString(),etag(buyer,"/suppliers/"+supplierA)).statusCode());
        assertEquals(1,get(buyer,"/purchasing-organizations/"+orgA).get("companyIds").size());
        assertEquals(404,call(buyer,"GET","/plants/"+plantB,null).statusCode());
    }
    @Test @Order(24) void sameGrantTupleCannotBeSplicedAcrossDimensions() throws Exception {
        ok(send(admin,"DELETE","/users/"+buyerId+"/grants/"+buyerGrant,null,true,UUID.randomUUID().toString(),etag(admin,"/users/"+buyerId)),200);
        grant(buyerId,"organization.read",companyA,plantA,orgA);grant(buyerId,"organization.read",companyA,plantA2,orgA2);
        assertEquals(1,get(buyer,"/plants?purchasingOrgId="+orgA).get("total").asInt());
        assertEquals(0,get(buyer,"/companies?plantId="+plantA+"&purchasingOrgId="+orgA2).get("total").asInt());
        assertEquals(1,get(buyer,"/companies?plantId="+plantA+"&purchasingOrgId="+orgA).get("total").asInt());
        assertEquals(0,get(buyer,"/suppliers").get("total").asInt());
        assertEquals(404,call(buyer,"GET","/suppliers/"+supplierA,null).statusCode());
        var invalid=Map.of("permission","organization.read","companyId",companyA,"plantId",plantB);
        assertEquals(404,send(admin,"POST","/users/"+buyerId+"/grants",invalid,true,UUID.randomUUID().toString(),etag(admin,"/users/"+buyerId)).statusCode());
        grant(buyerId,"supplier.manage",companyA,null,null);
    }
    @Test @Order(25) void supplierBindingAndCompanyGrantIntersect() throws Exception {
        grant(externalId,"supplier.read",companyA,null,null);grant(externalId,"supplier.read",companyB,null,null);
        ok(call(external,"POST","/auth/login",Map.of("login","supplier-a","password",externalPassword)),200);
        assertEquals(0,get(external,"/suppliers").get("total").asInt());assertEquals(0,get(external,"/companies").get("total").asInt());
        ok(send(admin,"PUT","/users/"+externalId+"/supplier-binding",Map.of("supplierId",supplierA),true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)),200);
        assertEquals(401,call(external,"GET","/auth/me",null).statusCode());ok(call(external,"POST","/auth/login",Map.of("login","supplier-a","password",externalPassword)),200);
        assertEquals(1,get(external,"/suppliers").get("total").asInt());assertEquals(2,get(external,"/suppliers/"+supplierA).get("companies").size());
        assertEquals(404,call(external,"GET","/suppliers/"+supplierB,null).statusCode());
        assertEquals(403,call(external,"POST","/suppliers",Map.of("code","E","name","Supplier E","companyIds",List.of(companyA))).statusCode());
        assertEquals(422,send(admin,"PUT","/users/"+externalId+"/roles",Map.of("roles",List.of("SUPPLIER","ADMIN")),true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)).statusCode());
        assertEquals(409,send(admin,"PUT","/users/"+externalId+"/roles",Map.of("roles",List.of("BUYER")),true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)).statusCode());
    }
    @Test @Order(26) void frozenSupplierRemainsReadableAndLinkRevocationIsImmediate() throws Exception {
        write(admin,"PATCH","/suppliers/"+supplierA,Map.of("name","Supplier A","status","FROZEN"),200);
        assertEquals("FROZEN",get(external,"/suppliers/"+supplierA).get("status").asString());
        ok(send(buyer,"POST","/suppliers/"+supplierA+"/companies",Map.of("companyId",companyA,"active",false),true,UUID.randomUUID().toString(),etag(buyer,"/suppliers/"+supplierA)),200);
        assertEquals(1,get(external,"/companies").get("total").asInt());assertEquals(404,call(external,"GET","/companies/"+companyA,null).statusCode());
        assertEquals(1,get(external,"/suppliers/"+supplierA).get("companies").size());
        ok(send(buyer,"POST","/suppliers/"+supplierA+"/companies",Map.of("companyId",companyA,"active",true),true,UUID.randomUUID().toString(),etag(buyer,"/suppliers/"+supplierA)),200);
        assertEquals(2,get(external,"/companies").get("total").asInt());
    }
    @Test @Order(27) void etagsIdempotencyAndRevocationGuardCachedResponses() throws Exception {
        grant(buyerId,"supplier.manage",companyB,null,null);sharedEtag=etag(buyer,"/suppliers/"+supplierA);
        var body=Map.of("name","Supplier A Updated","status","ACTIVE");
        var first=send(buyer,"PATCH","/suppliers/"+supplierA,body,true,sharedKey,sharedEtag);ok(first,200);
        assertEquals(first.body(),send(buyer,"PATCH","/suppliers/"+supplierA,body,true,sharedKey,sharedEtag).body());
        assertEquals(412,send(buyer,"PATCH","/suppliers/"+supplierA,body,true,UUID.randomUUID().toString(),sharedEtag).statusCode());
        String linkEtag=etag(buyer,"/suppliers/"+supplierA);var link=Map.of("companyId",companyA,"active",true);
        ok(send(buyer,"POST","/suppliers/"+supplierA+"/companies",link,true,"link-cache-0001",linkEtag),200);
        for(var g:get(admin,"/users/"+buyerId+"/grants")) if(g.get("companyId").asString().equals(companyB)) ok(send(admin,"DELETE","/users/"+buyerId+"/grants/"+id(g),null,true,UUID.randomUUID().toString(),etag(admin,"/users/"+buyerId)),200);
        assertEquals(404,send(buyer,"PATCH","/suppliers/"+supplierA,body,true,sharedKey,sharedEtag).statusCode());
        assertEquals(404,send(buyer,"POST","/suppliers/"+supplierA+"/companies",link,true,"link-cache-0001",linkEtag).statusCode());
        assertEquals(1,get(buyer,"/suppliers/"+supplierA).get("companies").size());
    }
    @Test @Order(28) void disabledHierarchyMakesRetainedGrantIneffective() throws Exception {
        write(admin,"PATCH","/companies/"+companyA,Map.of("code","A","name","Company A","timezone","Asia/Shanghai","active",false),200);
        assertEquals(0,get(buyer,"/companies").get("total").asInt());assertEquals(0,get(buyer,"/suppliers").get("total").asInt());assertEquals(0,get(buyer,"/auth/access").get("grants").size());
        write(admin,"PATCH","/companies/"+companyA,Map.of("code","A","name","Company A","timezone","Asia/Shanghai","active",true),200);
        assertEquals(1,get(buyer,"/suppliers").get("total").asInt());
        assertEquals(409,send(admin,"PATCH","/plants/"+plantA,Map.of("code","A1","name","Plant A1","companyId",companyB,"active",true),true,UUID.randomUUID().toString(),etag(admin,"/plants/"+plantA)).statusCode());
    }
    @Test @Order(29) void businessAuditRequiresCompanyLevelAuditGrant() throws Exception {
        assertEquals(0,get(regular,"/audit-events?size=100").get("items").findValuesAsString("operation").stream().filter(v->v.equals("supplier.create")||v.equals("company.create")).count());
        grant(userId,"audit.read",companyA,null,null);
        var items=get(regular,"/audit-events?size=100").get("items");
        assertTrue(items.findValuesAsString("operation").contains("supplier.create"));
        assertFalse(items.findValuesAsString("objectId").contains(supplierB));
    }
    @Test @Order(30) void roleWithoutScopeAndScopeWithoutRoleStayDenied() throws Exception {
        String password=UUID.randomUUID().toString();String user=id(ok(call(admin,"POST","/users",Map.of("login","limited-a","displayName","Limited User A","password",password,"roles",List.of("USER"))),201));
        grant(user,"supplier.read",companyA,plantA,orgA);
        var limited=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        ok(call(limited,"POST","/auth/login",Map.of("login","limited-a","password",password)),200);
        assertEquals(403,call(limited,"GET","/companies",null).statusCode());assertEquals(403,call(limited,"GET","/suppliers",null).statusCode());
        ok(send(admin,"PUT","/users/"+user+"/roles",Map.of("roles",List.of("BUYER")),true,UUID.randomUUID().toString(),etag(admin,"/users/"+user)),200);
        ok(call(limited,"POST","/auth/login",Map.of("login","limited-a","password",password)),200);
        assertEquals(1,get(limited,"/plants").get("total").asInt());assertEquals(0,get(limited,"/suppliers").get("total").asInt());
        write(admin,"PATCH","/plants/"+plantA,Map.of("code","A1","name","Plant A1","companyId",companyA,"active",false),200);
        assertEquals(0,get(limited,"/companies").get("total").asInt());
        write(admin,"PATCH","/plants/"+plantA,Map.of("code","A1","name","Plant A1","companyId",companyA,"active",true),200);
        write(admin,"PATCH","/purchasing-organizations/"+orgA,Map.of("code","A1","name","Purchasing A1","companyIds",List.of(companyB),"active",true),200);
        assertEquals(0,get(limited,"/companies").get("total").asInt());
        write(admin,"PATCH","/purchasing-organizations/"+orgA,Map.of("code","A1","name","Purchasing A1","companyIds",List.of(companyA,companyB),"active",false),200);
        assertEquals(0,get(limited,"/companies").get("total").asInt());
        write(admin,"PATCH","/purchasing-organizations/"+orgA,Map.of("code","A1","name","Purchasing A1","companyIds",List.of(companyA,companyB),"active",true),200);
        assertEquals(1,get(limited,"/companies").get("total").asInt());
    }
    @Test @Order(31) void validationDuplicatesAndQueryEscapingAreSafe() throws Exception {
        assertEquals(400,send(admin,"POST","/users/"+buyerId+"/grants",Map.of("permission","supplierXread","companyId",companyA),true,UUID.randomUUID().toString(),etag(admin,"/users/"+buyerId)).statusCode());
        assertEquals(409,call(admin,"POST","/companies",Map.of("code","a","name","Company Duplicate","timezone","UTC","active",true)).statusCode());
        assertEquals(400,call(admin,"POST","/companies",Map.of("code","C","name","Company C","timezone","invalid-zone","active",true)).statusCode());
        assertEquals(0,get(buyer,"/suppliers?query=%25").get("total").asInt());
        assertEquals(400,call(buyer,"GET","/suppliers?page=-1",null).statusCode());
        assertEquals(409,send(admin,"PATCH","/companies/"+companyA,Map.of("code","CHANGED","name","Company A","timezone","UTC","active",true),true,UUID.randomUUID().toString(),etag(admin,"/companies/"+companyA)).statusCode());
    }
    @Test @Order(32) void parallelSupplierSubmissionHasOneEffectAndOneAudit() throws Exception {
        var other=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();ok(call(other,"POST","/auth/login",Map.of("login","buyer-a","password",buyerPassword)),200);
        var input=Map.of("code","CONCURRENT","name","Supplier Concurrent","companyIds",List.of(companyA));
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->send(buyer,"POST","/suppliers",input,true,"supplier-parallel-01",null));
            var b=workers.submit(()->send(other,"POST","/suppliers",input,true,"supplier-parallel-01",null));
            ok(a.get(),201);ok(b.get(),201);assertEquals(a.get().body(),b.get().body());
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM supplier WHERE code='CONCURRENT'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=(SELECT id FROM supplier WHERE code='CONCURRENT')",Integer.class));
    }
    @Test @Order(33) void rebindingAndUnbindingInvalidateSupplierSession() throws Exception {
        ok(send(admin,"PUT","/users/"+externalId+"/supplier-binding",Map.of("supplierId",supplierB),true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)),200);
        assertEquals(401,call(external,"GET","/auth/me",null).statusCode());ok(call(external,"POST","/auth/login",Map.of("login","supplier-a","password",externalPassword)),200);
        assertEquals(404,call(external,"GET","/suppliers/"+supplierA,null).statusCode());assertEquals(supplierB,id(get(external,"/suppliers").get("items").get(0)));
        var body=new HashMap<String,Object>();body.put("supplierId",null);
        ok(send(admin,"PUT","/users/"+externalId+"/supplier-binding",body,true,UUID.randomUUID().toString(),etag(admin,"/users/"+externalId)),200);
        assertEquals(401,call(external,"GET","/auth/me",null).statusCode());ok(call(external,"POST","/auth/login",Map.of("login","supplier-a","password",externalPassword)),200);
        assertEquals(0,get(external,"/suppliers").get("total").asInt());
    }
}
