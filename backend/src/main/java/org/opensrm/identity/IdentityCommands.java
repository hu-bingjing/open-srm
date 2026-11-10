package org.opensrm.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.foundation.RequestIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Shared identity command port: authorization, serialization, audit and replay in one transaction. */
@Component
public class IdentityCommands {
    public record Outcome(Object body, UUID objectId, long version, String category, UUID supplierId, List<UUID> companies) {
        public static Outcome identity(Object body,UUID id,long version) { return new Outcome(body,id,version,"IDENTITY",null,List.of()); }
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key;
    public IdentityCommands(JdbcTemplate db,ObjectMapper json,@Value("${opensrm.idempotency-key}") String key) {
        this.db=db;this.json=json;this.key=key;
    }
    public void require(SessionUser actor,String permission) {
        boolean active=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=? AND status='ACTIVE' AND session_version=?)",Boolean.class,actor.id(),actor.sessionVersion()));
        if (!active) throw new ApiProblem(401,"UNAUTHENTICATED");
        if (permission!=null && !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role r JOIN role_permission p ON p.role_code=r.role_code WHERE r.user_id=? AND p.permission=?)",Boolean.class,actor.id(),permission)))
            throw new ApiProblem(403,"FORBIDDEN");
    }
    public static void match(long version,String etag) {
        if (!("\""+version+"\"").equals(etag)) throw new ApiProblem(412,"VERSION_CONFLICT");
    }
    private String serialized(Object value) { return json.writeValueAsString(value); }
    private String digest(Object payload) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(serialized(payload).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public IdentityService.StoredResult execute(SessionUser actor,String permission,String operation,String requestKey,Object payload,
        Runnable authorizeObject,Consumer<String> authorizeReplay,Supplier<Outcome> action) {
        db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class);
        require(actor,permission);
        authorizeObject.run(); // Before replay as well as before a new write.
        if (requestKey==null || requestKey.length()<8 || requestKey.length()>128) throw new ApiProblem(400,"INVALID_IDEMPOTENCY_KEY");
        String hash=digest(payload);
        var rows=db.query("SELECT request_hash,response_body,response_etag FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=?",
            (rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3)),actor.id(),operation,requestKey);
        if (!rows.isEmpty()) {
            var row=rows.getFirst();
            if (!MessageDigest.isEqual(row.getFirst().getBytes(StandardCharsets.UTF_8),hash.getBytes(StandardCharsets.UTF_8))) throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT");
            authorizeReplay.accept(row.get(1));
            return new IdentityService.StoredResult(row.get(1),row.get(2));
        }
        Outcome outcome=action.get();
        UUID event=UUID.randomUUID();
        db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id,category,supplier_id) SELECT ?,deployment_id,?,?,?,?,?,? FROM app_guard WHERE id=1",
            event,actor.id(),operation,outcome.objectId(),RequestIds.current(),outcome.category(),outcome.supplierId());
        for (UUID company:new HashSet<>(outcome.companies()))
            db.update("INSERT INTO audit_event_company(deployment_id,event_id,company_id) SELECT deployment_id,?,? FROM app_guard WHERE id=1",event,company);
        String body=serialized(outcome.body()),etag="\""+outcome.version()+"\"";
        db.update("INSERT INTO idempotency_record(actor_id,operation,request_key,request_hash,response_body,response_etag) VALUES (?,?,?,?,?,?)",
            actor.id(),operation,requestKey,hash,body,etag);
        return new IdentityService.StoredResult(body,etag);
    }
}
