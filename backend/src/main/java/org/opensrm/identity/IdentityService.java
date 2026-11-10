package org.opensrm.identity;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.foundation.RequestIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    public record UserView(UUID id, String login, String displayName, String status, List<String> roles, long version) { }
    public record Page<T>(List<T> items, long total, int page, int size) { }
    public record NewUser(@Pattern(regexp="[a-z0-9][a-z0-9._-]{2,79}") @NotBlank String login,
        @NotBlank @Size(max=120) String displayName, @NotBlank @Size(min=12,max=72) String password,
        @NotEmpty @Size(max=5) List<@Pattern(regexp="ADMIN|AUDITOR|USER|BUYER|SUPPLIER") String> roles) { }
    public record UpdateUser(@NotBlank @Size(max=120) String displayName, @Pattern(regexp="ACTIVE|DISABLED") @NotNull String status) { }
    public record UpdateProfile(@NotBlank @Size(max=120) String displayName) { }
    public record UpdateRoles(@NotEmpty @Size(max=5) List<@Pattern(regexp="ADMIN|AUDITOR|USER|BUYER|SUPPLIER") String> roles) { }
    public record AuditView(UUID id, UUID actorId, String operation, UUID objectId, UUID requestId, String createdAt) { }
    record Credentials(UUID id, String login, String hash, String status, long sessionVersion) { }
    public record StoredResult(String json, String etag) { }
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final IdentityCommands commands;
    public IdentityService(JdbcTemplate db, PasswordEncoder encoder, IdentityCommands commands) {
        this.db = db; this.encoder = encoder; this.commands = commands;
    }

    public Credentials credentials(String login) {
        var users = db.query("SELECT id, login, password_hash, status, session_version FROM app_user WHERE login=?",
            (rs,n) -> new Credentials(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)), login);
        return users.isEmpty() ? null : users.getFirst();
    }
    public Optional<SessionUser> activeSession(SessionUser session) {
        return db.query("SELECT id,login,session_version FROM app_user WHERE id=? AND status='ACTIVE' AND session_version=?",
            (rs,n) -> new SessionUser(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3)), session.id(),session.sessionVersion()).stream().findFirst();
    }
    public List<String> permissions(UUID id) {
        return db.queryForList("SELECT DISTINCT p.permission FROM role_permission p JOIN user_role r ON p.role_code=r.role_code WHERE r.user_id=? ORDER BY p.permission",String.class,id);
    }
    public UserView user(UUID id) {
        var users = db.query("SELECT id,login,display_name,status,lock_version FROM app_user WHERE id=?",
            (rs,n) -> new UserView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),List.of(),rs.getLong(5)), id);
        if (users.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");
        // Close the outer result/connection before querying roles to avoid pool exhaustion.
        var value=users.getFirst();
        return new UserView(value.id(),value.login(),value.displayName(),value.status(),
            db.queryForList("SELECT role_code FROM user_role WHERE user_id=? ORDER BY role_code",String.class,id),value.version());
    }
    public Page<UserView> users(int page, int size) {
        var ids = db.queryForList("SELECT id FROM app_user ORDER BY created_at,id LIMIT ? OFFSET ?",UUID.class,size,(long) page*size);
        return new Page<>(ids.stream().map(this::user).toList(),db.queryForObject("SELECT count(*) FROM app_user",Long.class),page,size);
    }
    public boolean initialized() { return Boolean.TRUE.equals(db.queryForObject("SELECT initialized FROM app_guard WHERE id=1",Boolean.class)); }
    private void lock() { db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class); }
    private void audit(UUID actor, String operation, UUID object) {
        db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1",
            UUID.randomUUID(),actor,operation,object,RequestIds.current());
    }
    private UUID insert(NewUser input) {
        validateRoles(input.roles());
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiProblem(422,"PASSWORD_TOO_LONG");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE login=?)",Boolean.class,input.login())))
            throw new ApiProblem(409,"LOGIN_UNAVAILABLE");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,deployment_id,login,display_name,password_hash,status) SELECT ?,deployment_id,?,?,?,'ACTIVE' FROM app_guard WHERE id=1",
            id,input.login(),input.displayName().trim(),encoder.encode(input.password()));
        roles(id,input.roles());
        return id;
    }
    private void roles(UUID id, List<String> roles) {
        db.update("DELETE FROM user_role WHERE user_id=?",id);
        new TreeSet<>(roles).forEach(role -> db.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,role));
    }
    static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private StoredResult command(SessionUser actor, String permission, String operation, String key, Object payload, Supplier<UserView> action) {
        return commands.execute(actor,permission,operation,key,payload,()->{},body->{},()->{
            var value=action.get();return IdentityCommands.Outcome.identity(value,value.id(),value.version());
        });
    }
    private void validateRoles(List<String> roles) {
        if (roles.contains("SUPPLIER") && new HashSet<>(roles).size()!=1) throw new ApiProblem(422,"ROLE_COMBINATION_NOT_ALLOWED");
    }
    private void match(UserView user, String etag) {
        if (!("\""+user.version()+"\"").equals(etag)) throw new ApiProblem(412,"VERSION_CONFLICT");
    }
    private void keepAdmin() {
        long count = db.queryForObject("SELECT count(*) FROM app_user u JOIN user_role r ON u.id=r.user_id WHERE u.status='ACTIVE' AND r.role_code='ADMIN'",Long.class);
        if (count==0) throw new ApiProblem(409,"LAST_ADMIN_REQUIRED");
    }
    @Transactional
    public UserView bootstrap(NewUser input, String supplied, String configured) {
        lock();
        if (initialized() || configured.length()<32 || supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),configured.getBytes(StandardCharsets.UTF_8)))
            throw new ApiProblem(404,"NOT_FOUND");
        UUID id=insert(new NewUser(input.login(),input.displayName(),input.password(),List.of("ADMIN")));
        db.update("UPDATE app_guard SET initialized=true WHERE id=1");
        audit(id,"identity.bootstrap",id);
        return user(id);
    }
    @Transactional
    public StoredResult create(SessionUser actor, String key, NewUser input) {
        // Password is only incorporated in the digest; never stored in audit or replay response.
        return command(actor,"user.manage","user.create",key,input,()->user(insert(input)));
    }
    @Transactional
    public StoredResult update(SessionUser actor, UUID id, String key, String etag, UpdateUser input) {
        return command(actor,"user.manage","user.update:"+id,key,List.of(etag,input),()->{
            match(user(id),etag);
            if (id.equals(actor.id()) && input.status().equals("DISABLED")) throw new ApiProblem(409,"SELF_DISABLE_FORBIDDEN");
            db.update("UPDATE app_user SET display_name=?,status=?,lock_version=lock_version+1,session_version=session_version+CASE WHEN status<>? THEN 1 ELSE 0 END WHERE id=?",
                input.displayName().trim(),input.status(),input.status(),id);
            keepAdmin(); return user(id);
        });
    }
    @Transactional
    public StoredResult updateRoles(SessionUser actor, UUID id, String key, String etag, UpdateRoles input) {
        return command(actor,"user.manage","user.roles:"+id,key,List.of(etag,input),()->{
            match(user(id),etag);
            validateRoles(input.roles());
            if (!input.roles().contains("SUPPLIER") && Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM user_supplier WHERE user_id=?)",Boolean.class,id)))
                throw new ApiProblem(409,"BINDING_MUST_BE_REMOVED");
            roles(id,input.roles()); keepAdmin();
            db.update("UPDATE app_user SET lock_version=lock_version+1,session_version=session_version+1 WHERE id=?",id);
            return user(id);
        });
    }
    @Transactional
    public StoredResult profile(SessionUser actor, String key, String etag, UpdateProfile input) {
        return command(actor,null,"account.profile",key,List.of(etag,input),()->{
            match(user(actor.id()),etag);
            db.update("UPDATE app_user SET display_name=?,lock_version=lock_version+1 WHERE id=?",input.displayName().trim(),actor.id());
            return user(actor.id());
        });
    }
    @Transactional
    public void loginAudit(UUID id) { audit(id,"auth.login",id); }
    @Transactional
    public void logoutAudit(UUID id) { audit(id,"auth.logout",id); }
    public boolean loginBlocked(String login) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM login_attempt WHERE login_hash=? AND failures>=5 AND blocked_until>CURRENT_TIMESTAMP)",Boolean.class,digest(login)));
    }
    @Transactional
    public void loginFailed(String login) {
        db.update("DELETE FROM login_attempt WHERE updated_at<CURRENT_TIMESTAMP-INTERVAL '1 day'");
        db.update("INSERT INTO login_attempt(login_hash,failures,blocked_until) VALUES (?,1,CURRENT_TIMESTAMP+INTERVAL '5 minutes') ON CONFLICT(login_hash) DO UPDATE SET failures=CASE WHEN login_attempt.blocked_until<CURRENT_TIMESTAMP THEN 1 ELSE login_attempt.failures+1 END,blocked_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes',updated_at=CURRENT_TIMESTAMP",digest(login));
    }
    public void loginSucceeded(String login) { db.update("DELETE FROM login_attempt WHERE login_hash=?",digest(login)); }
}
