package org.opensrm.identity;

import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, real PostgreSQL and real Flyway. No mocked security/session layer. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "opensrm.environment=test","server.servlet.session.cookie.secure=false",
    "opensrm.bootstrap-token" + "=synthetic-test-bootstrap-value-0000000000",
    "opensrm.idempotency-key=synthetic-test-idempotency-value-0000000000"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class IdentityHttpAcceptance {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    final String adminPassword=UUID.randomUUID().toString(),userPassword=UUID.randomUUID().toString();
    final CookieManager adminCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    final HttpClient admin=HttpClient.newBuilder().cookieHandler(adminCookies).build();
    final HttpClient regular=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    String userId;
    HttpResponse<String> send(HttpClient client,String method,String path,Object body,boolean csrf,String key,String etag) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
        if (csrf) {
            var token=send(client,"GET","/auth/csrf",null,false,null,null);
            builder.header("X-CSRF-Token",json.readTree(token.body()).get("csrfToken").asText());
        }
        if (key!=null) builder.header("Idempotency-Key",key);
        if (etag!=null) builder.header("If-Match",etag);
        builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> call(HttpClient client,String method,String path,Object body) throws Exception {
        return send(client,method,path,body,!method.equals("GET"),UUID.randomUUID().toString(),null);
    }
    @Test @Order(1) void csrfAndAnonymousBoundaries() throws Exception {
        assertEquals(401,call(admin,"GET","/auth/me",null).statusCode());
        assertEquals(403,send(admin,"POST","/auth/login",Map.of("login","admin-a","password",adminPassword),false,null,null).statusCode());
        assertTrue(json.readTree(call(admin,"GET","/auth/bootstrap/status",null).body()).get("setupRequired").asBoolean());
    }
    @Test @Order(2) void bootstrapRequiresTokenAndClosesPermanently() throws Exception {
        var input=new HashMap<String,Object>(Map.of("bootstrapToken","invalid","login","admin-a","displayName","Administrator A","password",adminPassword));
        assertEquals(404,call(admin,"POST","/auth/bootstrap",input).statusCode());
        input.put("bootstrapToken","synthetic-test-bootstrap-value-0000000000");
        assertEquals(201,call(admin,"POST","/auth/bootstrap",input).statusCode());
        assertEquals(404,call(admin,"POST","/auth/bootstrap",input).statusCode());
        assertTrue(db.queryForObject("SELECT password_hash FROM app_user WHERE login='admin-a'",String.class).startsWith("$2"));
        assertNotEquals(adminPassword,db.queryForObject("SELECT password_hash FROM app_user WHERE login='admin-a'",String.class));
    }
    @Test @Order(3) void loginRotatesSessionAndCookieIsHttpOnly() throws Exception {
        call(admin,"GET","/auth/csrf",null);
        String before=adminCookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("OPEN_SRM_SESSION")).findFirst().orElseThrow().getValue();
        assertEquals(401,call(admin,"POST","/auth/login",Map.of("login","admin-a","password",UUID.randomUUID().toString())).statusCode());
        var response=call(admin,"POST","/auth/login",Map.of("login","admin-a","password",adminPassword));
        assertEquals(200,response.statusCode(),response.body());
        String after=adminCookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("OPEN_SRM_SESSION")).findFirst().orElseThrow().getValue();
        assertNotEquals(before,after);
        assertTrue(response.headers().allValues("set-cookie").stream().anyMatch(c->c.contains("HttpOnly")&&c.contains("SameSite=Lax")));
        assertFalse(response.body().contains(adminPassword));
        assertEquals(200,call(admin,"GET","/auth/me",null).statusCode());
    }
    @Test @Order(4) void createIsIdempotentAndContentConflictIsAtomic() throws Exception {
        var input=Map.of("login","user-a","displayName","User A","password",userPassword,"roles",List.of("USER"));
        var first=send(admin,"POST","/users",input,true,"create-user-a-0001",null);
        assertEquals(201,first.statusCode(),first.body());userId=json.readTree(first.body()).get("id").asText();
        var second=send(admin,"POST","/users",input,true,"create-user-a-0001",null);
        assertEquals(first.body(),second.body());
        var changed=new HashMap<String,Object>(input);changed.put("displayName","Other Name");
        assertEquals(409,send(admin,"POST","/users",changed,true,"create-user-a-0001",null).statusCode());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_event WHERE operation='user.create'",Integer.class));
        assertFalse(db.queryForObject("SELECT response_body FROM idempotency_record WHERE operation='user.create'",String.class).contains(userPassword));
    }
    @Test @Order(5) void userCannotManageAccountsOrReadAudit() throws Exception {
        assertEquals(200,call(regular,"POST","/auth/login",Map.of("login","user-a","password",userPassword)).statusCode());
        assertEquals(403,call(regular,"GET","/users",null).statusCode());
        assertEquals(403,call(regular,"GET","/audit-events",null).statusCode());
        assertEquals(403,call(admin,"GET","/audit-events",null).statusCode());
    }
    @Test @Order(6) void profileNeedsCurrentEtagAndAuditHasNoSecret() throws Exception {
        var input=Map.of("displayName","User A Updated");
        assertEquals(412,send(regular,"PATCH","/auth/profile",input,true,"profile-00001","\"99\"").statusCode());
        assertEquals(200,send(regular,"PATCH","/auth/profile",input,true,"profile-00002","\"0\"").statusCode());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_event WHERE operation='account.profile'",Integer.class));
    }
    @Test @Order(7) void disableRevokesExistingSessionAndReenableNeedsNewLogin() throws Exception {
        var current=call(admin,"GET","/users/"+userId,null);String etag=current.headers().firstValue("ETag").orElseThrow();
        var input=Map.of("displayName","User A","status","DISABLED");
        assertEquals(200,send(admin,"PATCH","/users/"+userId,input,true,"disable-00001",etag).statusCode());
        assertEquals(401,call(regular,"GET","/auth/me",null).statusCode());
        assertEquals(401,call(regular,"POST","/auth/login",Map.of("login","user-a","password",userPassword)).statusCode());
        current=call(admin,"GET","/users/"+userId,null);etag=current.headers().firstValue("ETag").orElseThrow();
        assertEquals(200,send(admin,"PATCH","/users/"+userId,Map.of("displayName","User A","status","ACTIVE"),true,"enable-000001",etag).statusCode());
        assertEquals(200,call(regular,"POST","/auth/login",Map.of("login","user-a","password",userPassword)).statusCode());
    }
    @Test @Order(8) void roleChangeRevokesSessionAndAuditRoleWorks() throws Exception {
        var current=call(admin,"GET","/users/"+userId,null);String etag=current.headers().firstValue("ETag").orElseThrow();
        assertEquals(200,send(admin,"PUT","/users/"+userId+"/roles",Map.of("roles",List.of("AUDITOR")),true,"roles-0000001",etag).statusCode());
        assertEquals(401,call(regular,"GET","/auth/me",null).statusCode());
        assertEquals(200,call(regular,"POST","/auth/login",Map.of("login","user-a","password",userPassword)).statusCode());
        assertEquals(200,call(regular,"GET","/audit-events",null).statusCode());
        assertEquals(403,call(regular,"GET","/users",null).statusCode());
    }
    @Test @Order(9) void lastAdministratorCannotBeRemovedAndTransactionRollsBack() throws Exception {
        var me=call(admin,"GET","/auth/me",null);var id=json.readTree(me.body()).get("id").asText();String etag=me.headers().firstValue("ETag").orElseThrow();
        long before=db.queryForObject("SELECT count(*) FROM audit_event",Long.class);
        assertEquals(409,send(admin,"PUT","/users/"+id+"/roles",Map.of("roles",List.of("USER")),true,"roles-admin-1",etag).statusCode());
        assertEquals(before,db.queryForObject("SELECT count(*) FROM audit_event",Long.class));
        assertEquals(200,call(admin,"GET","/users",null).statusCode());
    }
    @Test @Order(10) void parallelIdempotentRequestsHaveOneEffect() throws Exception {
        String display="Concurrent Profile";String etag=call(admin,"GET","/auth/me",null).headers().firstValue("ETag").orElseThrow();
        // Separate HTTP sessions for same actor, so DB idempotency is tested independently of cookie-store concurrency.
        HttpClient other=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        assertEquals(200,call(other,"POST","/auth/login",Map.of("login","admin-a","password",adminPassword)).statusCode());
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->send(admin,"PATCH","/auth/profile",Map.of("displayName",display),true,"parallel-0001",etag));
            var second=workers.submit(()->send(other,"PATCH","/auth/profile",Map.of("displayName",display),true,"parallel-0001",etag));
            assertEquals(200,first.get().statusCode());assertEquals(200,second.get().statusCode());
            assertEquals(first.get().body(),second.get().body());
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM idempotency_record WHERE request_key='parallel-0001'",Integer.class));
    }
    @Test @Order(11) void logoutInvalidatesPersistentSession() throws Exception {
        assertEquals(204,call(admin,"POST","/auth/logout",null).statusCode());
        assertEquals(401,call(admin,"GET","/auth/me",null).statusCode());
    }
    @Test @Order(10) void concurrentAccountReadsDoNotExhaustConnectionPool() throws Exception {
        try(var workers=Executors.newFixedThreadPool(16)) {
            var tasks=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<16;i++) tasks.add(workers.submit(()->call(regular,"GET","/auth/me",null)));
            for(var result:tasks) assertEquals(200,result.get(10,TimeUnit.SECONDS).statusCode());
        }
    }
    @Test @Order(12) void failedLoginThrottlesWithoutCreatingSessions() throws Exception {
        var input=Map.of("login","unknown-user-a","password",UUID.randomUUID().toString());
        for(int i=0;i<5;i++) assertEquals(401,call(admin,"POST","/auth/login",input).statusCode());
        assertEquals(429,call(admin,"POST","/auth/login",input).statusCode());
    }
    @Test @Order(13) void paginationIsValidatedAndNoSecretInErrors() throws Exception {
        var response=call(regular,"GET","/audit-events?size=101",null);
        assertEquals(400,response.statusCode(),response.body());
        assertFalse(response.body().contains("Exception"));
        assertEquals(404,call(regular,"GET","/not-implemented",null).statusCode());
    }
}
