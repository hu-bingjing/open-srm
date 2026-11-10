package org.opensrm.access;

import java.util.*;
import jakarta.validation.constraints.*;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
    public record GrantInput(@NotNull @Pattern(regexp="organization[.]read|supplier[.]read|supplier[.]manage|audit[.]read|order[.]read|sync[.]manage|confirmation[.]submit|delivery[.]approve|asn[.]manage") String permission,@NotNull UUID companyId,UUID plantId,UUID purchasingOrgId) { }
    public record GrantView(UUID id,String permission,UUID companyId,UUID plantId,UUID purchasingOrgId) { }
    public record Binding(UUID supplierId) { }
    public record AccessView(List<String> permissions,List<GrantView> grants,UUID supplierId) { }
    private final JdbcTemplate db;private final IdentityCommands commands;private final IdentityService identities;private final AccessPolicy policy;
    public AccessService(JdbcTemplate db,IdentityCommands commands,IdentityService identities,AccessPolicy policy) { this.db=db;this.commands=commands;this.identities=identities;this.policy=policy; }
    private List<GrantView> grants(UUID user) { return db.query("SELECT * FROM organization_grant WHERE user_id=? AND active ORDER BY created_at,id",(r,n)->new GrantView(r.getObject("id",UUID.class),r.getString("permission"),r.getObject("company_id",UUID.class),r.getObject("plant_id",UUID.class),r.getObject("purchasing_org_id",UUID.class)),user); }
    private Binding binding(UUID user) { var rows=db.queryForList("SELECT supplier_id FROM user_supplier WHERE user_id=?",UUID.class,user);return new Binding(rows.isEmpty()?null:rows.getFirst()); }
    public AccessView own(SessionUser actor) {
        commands.require(actor,null);
        var permissions=db.queryForList("SELECT DISTINCT p.permission FROM user_role r JOIN role_permission p ON p.role_code=r.role_code WHERE r.user_id=? ORDER BY p.permission",String.class,actor.id());
        // Only effective grants are exposed as current access; administrators see retained grants in the management endpoint.
        var ctx=new AccessPolicy.Context(actor,Set.copyOf(permissions),false,null);
        var effective=new NamedParameterJdbcTemplate(db).query("SELECT g.* FROM organization_grant g WHERE "+policy.effectiveGrant()+" ORDER BY g.created_at,g.id",policy.parameters(ctx),(r,n)->new GrantView(r.getObject("id",UUID.class),r.getString("permission"),r.getObject("company_id",UUID.class),r.getObject("plant_id",UUID.class),r.getObject("purchasing_org_id",UUID.class)));
        return new AccessView(permissions,effective,binding(actor.id()).supplierId());
    }
    public List<GrantView> list(SessionUser actor,UUID user) { commands.require(actor,"scope.manage");identities.user(user);return grants(user); }
    public Binding getBinding(SessionUser actor,UUID user) { commands.require(actor,"scope.manage");identities.user(user);return binding(user); }
    private void version(UUID user,String etag) { IdentityCommands.match(identities.user(user).version(),etag); }
    private IdentityCommands.Outcome result(UUID user) { db.update("UPDATE app_user SET lock_version=lock_version+1 WHERE id=?",user);var v=identities.user(user);return IdentityCommands.Outcome.identity(v,user,v.version()); }
    public IdentityService.StoredResult add(SessionUser actor,UUID user,String key,String etag,GrantInput input) {
        return commands.execute(actor,"scope.manage","scope.add:"+user,key,List.of(etag,input),()->{identities.user(user);policy.validateScope(input.companyId(),input.plantId(),input.purchasingOrgId());},v->{},()->{
            version(user,etag);
            boolean duplicate=grants(user).stream().anyMatch(g->g.permission().equals(input.permission()) && g.companyId().equals(input.companyId()) && Objects.equals(g.plantId(),input.plantId()) && Objects.equals(g.purchasingOrgId(),input.purchasingOrgId()));
            if(duplicate) throw new ApiProblem(409,"GRANT_ALREADY_EXISTS");
            db.update("INSERT INTO organization_grant(id,deployment_id,user_id,permission,company_id,plant_id,purchasing_org_id) SELECT ?,deployment_id,?,?,?,?,? FROM app_guard WHERE id=1",UUID.randomUUID(),user,input.permission(),input.companyId(),input.plantId(),input.purchasingOrgId());return result(user);
        });
    }
    public IdentityService.StoredResult revoke(SessionUser actor,UUID user,UUID grant,String key,String etag) {
        return commands.execute(actor,"scope.manage","scope.revoke:"+grant,key,List.of(user,etag),()->identities.user(user),v->{},()->{
            version(user,etag);
            if(db.update("UPDATE organization_grant SET active=false WHERE id=? AND user_id=? AND active",grant,user)!=1) throw new ApiProblem(404,"NOT_FOUND");return result(user);
        });
    }
    public IdentityService.StoredResult bind(SessionUser actor,UUID user,String key,String etag,Binding input) {
        return commands.execute(actor,"scope.manage","supplier.bind:"+user,key,List.of(etag,input),()->{
            var target=identities.user(user);
            if(!target.roles().equals(List.of("SUPPLIER"))) throw new ApiProblem(422,"SUPPLIER_ROLE_REQUIRED");
            if(input.supplierId()!=null) policy.requireSupplierRead(policy.context(actor,"supplier.read"),input.supplierId());
        },v->{},()->{
            version(user,etag);db.update("DELETE FROM user_supplier WHERE user_id=?",user);
            if(input.supplierId()!=null) db.update("INSERT INTO user_supplier(user_id,deployment_id,supplier_id) SELECT ?,deployment_id,? FROM app_guard WHERE id=1",user,input.supplierId());
            db.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?",user);return result(user);
        });
    }
}
