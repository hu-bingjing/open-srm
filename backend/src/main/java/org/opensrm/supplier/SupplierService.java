package org.opensrm.supplier;

import java.util.*;
import jakarta.validation.constraints.*;
import tools.jackson.databind.ObjectMapper;
import org.opensrm.access.AccessPolicy;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;

@Service
public class SupplierService {
    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,39}") String code,@NotBlank @Size(max=160) String name,@NotEmpty @Size(max=100) List<@NotNull UUID> companyIds) { }
    public record Update(@NotBlank @Size(max=160) String name,@NotNull @Pattern(regexp="ACTIVE|FROZEN") String status) { }
    public record Link(@NotNull UUID companyId,@NotNull Boolean active) { }
    public record CompanyLink(UUID companyId,String companyCode,String companyName,boolean active) { }
    public record View(UUID id,String code,String name,String status,long version,List<CompanyLink> companies,boolean canEdit) { }
    private final JdbcTemplate db; private final NamedParameterJdbcTemplate sql; private final AccessPolicy policy; private final IdentityCommands commands; private final ObjectMapper json;
    public SupplierService(JdbcTemplate db,AccessPolicy policy,IdentityCommands commands,ObjectMapper json) { this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.commands=commands;this.json=json; }
    private View enrich(View v,AccessPolicy.Context ctx) {
        var links=sql.query("SELECT c.id,c.code,c.name,sc.active FROM supplier_company sc JOIN company c ON c.id=sc.company_id WHERE sc.supplier_id=:id AND c.active AND "+policy.companyRead(ctx,"c.id")+(ctx.supplierUser()?" AND sc.active":"")+" ORDER BY c.code",policy.parameters(ctx).addValue("id",v.id()),(r,n)->new CompanyLink(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4)));
        return new View(v.id(),v.code(),v.name(),v.status(),v.version(),links,policy.canEditSupplier(ctx,v.id()));
    }
    public IdentityService.Page<View> list(SessionUser actor,int page,int size,String query,UUID companyId) {
        var ctx=policy.context(actor,"supplier.read");var args=policy.parameters(ctx).addValue("query",AccessPolicy.search(query)).addValue("limit",size).addValue("offset",(long)page*size);
        if(companyId!=null) args.addValue("company",companyId);
        String where="s.deployment_id="+AccessPolicy.REALM+" AND "+policy.supplierVisible(ctx,"s.id",companyId==null?null:":company")+" AND (lower(s.code) LIKE :query OR lower(s.name) LIKE :query)";
        var items=sql.query("SELECT s.* FROM supplier s WHERE "+where+" ORDER BY s.created_at,s.id LIMIT :limit OFFSET :offset",args,(r,n)->new View(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("status"),r.getLong("lock_version"),List.of(),false));
        return new IdentityService.Page<>(items.stream().map(v->enrich(v,ctx)).toList(),sql.queryForObject("SELECT count(*) FROM supplier s WHERE "+where,args,Long.class),page,size);
    }
    public View get(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"supplier.read");var rows=sql.query("SELECT s.* FROM supplier s WHERE s.id=:id AND s.deployment_id="+AccessPolicy.REALM+" AND "+policy.supplierVisible(ctx,"s.id",null),policy.parameters(ctx).addValue("id",id),(r,n)->new View(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("status"),r.getLong("lock_version"),List.of(),false));
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return enrich(rows.getFirst(),ctx);
    }
    private void replay(SessionUser actor,String body) {
        var ctx=policy.context(actor,"supplier.read");var value=json.readTree(body);policy.requireSupplierRead(ctx,UUID.fromString(value.get("id").asString()));
        for(var link:value.get("companies")) {
            UUID company=UUID.fromString(link.get("companyId").asString());
            boolean allowed=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM company c WHERE c.id=:company AND c.active AND "+policy.companyRead(ctx,"c.id")+")",policy.parameters(ctx).addValue("company",company),Boolean.class));
            if(!allowed) throw new ApiProblem(404,"NOT_FOUND");
        }
    }
    private IdentityCommands.Outcome outcome(SessionUser actor,UUID id,List<UUID> companies) { var v=get(actor,id);return new IdentityCommands.Outcome(v,id,v.version(),"SUPPLIER",id,companies); }
    public IdentityService.StoredResult create(SessionUser actor,String key,Create input) {
        return commands.execute(actor,"supplier.manage","supplier.create",key,input,()->{
            if(new HashSet<>(input.companyIds()).size()!=input.companyIds().size()) throw new ApiProblem(422,"DUPLICATE_COMPANY");
            var ctx=policy.context(actor,"supplier.manage");input.companyIds().forEach(id->policy.requireCompanyManage(ctx,id));
        },body->replay(actor,body),()->{
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM supplier WHERE deployment_id="+AccessPolicy.REALM+" AND lower(code)=lower(?))",Boolean.class,input.code()))) throw new ApiProblem(409,"CODE_UNAVAILABLE");
            UUID id=UUID.randomUUID();db.update("INSERT INTO supplier(id,deployment_id,code,name,status) SELECT ?,deployment_id,?,?,'ACTIVE' FROM app_guard WHERE id=1",id,input.code(),input.name().trim());
            for(UUID company:input.companyIds()) link(id,company,true);
            return outcome(actor,id,input.companyIds());
        });
    }
    public IdentityService.StoredResult update(SessionUser actor,UUID id,String key,String etag,Update input) {
        return commands.execute(actor,"supplier.manage","supplier.update:"+id,key,List.of(etag,input),()->policy.requireFullSupplierManage(policy.context(actor,"supplier.manage"),id),body->replay(actor,body),()->{
            IdentityCommands.match(get(actor,id).version(),etag);
            db.update("UPDATE supplier SET name=?,status=?,lock_version=lock_version+1 WHERE id=?",input.name().trim(),input.status(),id);
            return outcome(actor,id,db.queryForList("SELECT company_id FROM supplier_company WHERE supplier_id=?",UUID.class,id));
        });
    }
    private void link(UUID supplier,UUID company,boolean active) { db.update("INSERT INTO supplier_company(deployment_id,supplier_id,company_id,active) SELECT deployment_id,?,?,? FROM app_guard WHERE id=1 ON CONFLICT(deployment_id,supplier_id,company_id) DO UPDATE SET active=excluded.active",supplier,company,active); }
    public IdentityService.StoredResult associate(SessionUser actor,UUID id,String key,String etag,Link input) {
        return commands.execute(actor,"supplier.manage","supplier.company:"+id,key,List.of(etag,input),()->{var ctx=policy.context(actor,"supplier.manage");policy.requireSupplierRead(ctx,id);policy.requireCompanyManage(ctx,input.companyId());},body->replay(actor,body),()->{
            IdentityCommands.match(get(actor,id).version(),etag);link(id,input.companyId(),input.active());db.update("UPDATE supplier SET lock_version=lock_version+1 WHERE id=?",id);
            return outcome(actor,id,List.of(input.companyId()));
        });
    }
}
