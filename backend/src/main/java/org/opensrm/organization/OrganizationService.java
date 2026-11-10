package org.opensrm.organization;

import java.time.ZoneId;
import java.util.*;
import jakarta.validation.constraints.*;
import org.opensrm.access.AccessPolicy;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;

@Service
public class OrganizationService {
    public record CompanyInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,39}") String code,@NotBlank @Size(max=160) String name,@NotBlank @Size(max=80) String timezone,@NotNull Boolean active) { }
    public record PlantInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,39}") String code,@NotBlank @Size(max=160) String name,@NotNull UUID companyId,@NotNull Boolean active) { }
    public record PurchasingInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,39}") String code,@NotBlank @Size(max=160) String name,@NotEmpty @Size(max=100) List<@NotNull UUID> companyIds,@NotNull Boolean active) { }
    public record CompanyView(UUID id,String code,String name,String timezone,boolean active,long version) { }
    public record PlantView(UUID id,String code,String name,UUID companyId,boolean active,long version) { }
    public record PurchasingView(UUID id,String code,String name,List<UUID> companyIds,boolean active,long version) { }
    private static final RowMapper<CompanyView> COMPANY=(r,n)->new CompanyView(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("timezone"),r.getBoolean("active"),r.getLong("lock_version"));
    private static final RowMapper<PlantView> PLANT=(r,n)->new PlantView(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getObject("company_id",UUID.class),r.getBoolean("active"),r.getLong("lock_version"));
    private static final RowMapper<PurchasingView> PURCHASING=(r,n)->new PurchasingView(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),List.of(),r.getBoolean("active"),r.getLong("lock_version"));
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate sql;
    private final AccessPolicy policy;
    private final IdentityCommands commands;
    public OrganizationService(JdbcTemplate db,AccessPolicy policy,IdentityCommands commands) { this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.commands=commands; }
    private String search(String query,MapSqlParameterSource args,String alias) {
        args.addValue("query",AccessPolicy.search(query));return " AND (lower("+alias+".code) LIKE :query OR lower("+alias+".name) LIKE :query)";
    }
    private <T> IdentityService.Page<T> page(String from,String where,String alias,MapSqlParameterSource args,int page,int size,RowMapper<T> mapper) {
        args.addValue("limit",size).addValue("offset",(long)page*size);
        var values=sql.query("SELECT "+alias+".* FROM "+from+" WHERE "+where+" ORDER BY "+alias+".created_at,"+alias+".id LIMIT :limit OFFSET :offset",args,mapper);
        return new IdentityService.Page<>(values,sql.queryForObject("SELECT count(*) FROM "+from+" WHERE "+where,args,Long.class),page,size);
    }
    private String companyWhere(AccessPolicy.Context ctx,UUID plant,UUID org,MapSqlParameterSource args) {
        String where="c.deployment_id="+AccessPolicy.REALM+" AND "+policy.catalog(ctx,"c.id",plant==null?null:":plant",org==null?null:":org");
        if (plant!=null) { args.addValue("plant",plant);where+=" AND EXISTS(SELECT 1 FROM plant p WHERE p.id=:plant AND p.company_id=c.id AND p.active)"; }
        if (org!=null) { args.addValue("org",org);where+=" AND EXISTS(SELECT 1 FROM company_purch_org cp JOIN purchasing_org po ON po.id=cp.purchasing_org_id WHERE cp.company_id=c.id AND cp.purchasing_org_id=:org AND cp.active AND po.active)"; }
        return where;
    }
    public IdentityService.Page<CompanyView> companies(SessionUser actor,int page,int size,String query,UUID plant,UUID org) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx);
        String where=companyWhere(ctx,plant,org,args)+search(query,args,"c");
        return page("company c",where,"c",args,page,size,COMPANY);
    }
    public CompanyView company(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx).addValue("id",id);
        var rows=sql.query("SELECT c.* FROM company c WHERE c.id=:id AND "+companyWhere(ctx,null,null,args),args,COMPANY);
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
    }
    private String plantWhere(AccessPolicy.Context ctx,UUID company,UUID org,MapSqlParameterSource args) {
        String where="p.deployment_id="+AccessPolicy.REALM+" AND "+policy.catalog(ctx,"p.company_id","p.id",org==null?null:":org");
        if (!ctx.permissions().contains("organization.manage")) where+=" AND p.active";
        if (company!=null) {args.addValue("company",company);where+=" AND p.company_id=:company";}
        if (org!=null) {args.addValue("org",org);where+=" AND EXISTS(SELECT 1 FROM company_purch_org cp JOIN purchasing_org po ON po.id=cp.purchasing_org_id WHERE cp.company_id=p.company_id AND po.id=:org AND cp.active AND po.active)";}
        return where;
    }
    public IdentityService.Page<PlantView> plants(SessionUser actor,int page,int size,String query,UUID company,UUID org) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx);
        return page("plant p",plantWhere(ctx,company,org,args)+search(query,args,"p"),"p",args,page,size,PLANT);
    }
    public PlantView plant(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx).addValue("id",id);
        var rows=sql.query("SELECT p.* FROM plant p WHERE p.id=:id AND "+plantWhere(ctx,null,null,args),args,PLANT);
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst();
    }
    private String purchasingCompanies(AccessPolicy.Context ctx,UUID company,UUID plant,MapSqlParameterSource args) {
        String where="cp.purchasing_org_id=po.id AND cp.active AND "+policy.catalog(ctx,"cp.company_id",plant==null?null:":plant","po.id");
        if(company!=null) {args.addValue("company",company);where+=" AND cp.company_id=:company";}
        if(plant!=null) {args.addValue("plant",plant);where+=" AND EXISTS(SELECT 1 FROM plant p WHERE p.id=:plant AND p.company_id=cp.company_id AND p.active)";}
        return where;
    }
    private PurchasingView withCompanies(PurchasingView value,AccessPolicy.Context ctx) {
        var args=policy.parameters(ctx).addValue("id",value.id());
        var companies=sql.queryForList("SELECT cp.company_id FROM company_purch_org cp JOIN purchasing_org po ON po.id=cp.purchasing_org_id WHERE po.id=:id AND "+purchasingCompanies(ctx,null,null,args)+" ORDER BY cp.company_id",args,UUID.class);
        return new PurchasingView(value.id(),value.code(),value.name(),companies,value.active(),value.version());
    }
    public IdentityService.Page<PurchasingView> purchasingOrganizations(SessionUser actor,int page,int size,String query,UUID company,UUID plant) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx);
        String where="po.deployment_id="+AccessPolicy.REALM;
        if(!ctx.permissions().contains("organization.manage")) where+=" AND po.active";
        if(!ctx.permissions().contains("organization.manage") || company!=null || plant!=null)
            where+=" AND EXISTS(SELECT 1 FROM company_purch_org cp WHERE "+purchasingCompanies(ctx,company,plant,args)+")";
        where+=" AND "+policy.currentActor()+search(query,args,"po");
        var result=page("purchasing_org po",where,"po",args,page,size,PURCHASING);
        return new IdentityService.Page<>(result.items().stream().map(v->withCompanies(v,ctx)).toList(),result.total(),page,size);
    }
    public PurchasingView purchasingOrganization(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"organization.read");var args=policy.parameters(ctx).addValue("id",id);
        String where="po.id=:id AND po.deployment_id="+AccessPolicy.REALM+" AND "+policy.currentActor();
        if(!ctx.permissions().contains("organization.manage")) where+=" AND po.active AND EXISTS(SELECT 1 FROM company_purch_org cp WHERE "+purchasingCompanies(ctx,null,null,args)+")";
        var rows=sql.query("SELECT po.* FROM purchasing_org po WHERE "+where,args,PURCHASING);
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return withCompanies(rows.getFirst(),ctx);
    }
    private void codeUnchanged(String old,String requested) { if(!old.equals(requested)) throw new ApiProblem(409,"CODE_IMMUTABLE"); }
    private void unique(String table,String code,UUID company) {
        String query="SELECT EXISTS(SELECT 1 FROM "+table+" WHERE deployment_id="+AccessPolicy.REALM+" AND lower(code)=lower(?)"+(company==null?"":" AND company_id=?")+")";
        boolean duplicate=company==null?Boolean.TRUE.equals(db.queryForObject(query,Boolean.class,code)):Boolean.TRUE.equals(db.queryForObject(query,Boolean.class,code,company));
        if(duplicate) throw new ApiProblem(409,"CODE_UNAVAILABLE");
    }
    private IdentityCommands.Outcome outcome(Object body,UUID id,long version,List<UUID> companies) { return new IdentityCommands.Outcome(body,id,version,"ORGANIZATION",null,companies); }
    public IdentityService.StoredResult createCompany(SessionUser actor,String key,CompanyInput input) {
        return commands.execute(actor,"organization.manage","company.create",key,input,()->ZoneId.of(input.timezone()),v->{},()->{
            unique("company",input.code(),null);UUID id=UUID.randomUUID();
            db.update("INSERT INTO company(id,deployment_id,code,name,timezone,active) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1",id,input.code(),input.name().trim(),input.timezone(),input.active());
            var value=company(actor,id);return outcome(value,id,value.version(),List.of(id));
        });
    }
    public IdentityService.StoredResult updateCompany(SessionUser actor,UUID id,String key,String etag,CompanyInput input) {
        return commands.execute(actor,"organization.manage","company.update:"+id,key,List.of(etag,input),()->{company(actor,id);ZoneId.of(input.timezone());},v->{},()->{
            var old=company(actor,id);IdentityCommands.match(old.version(),etag);codeUnchanged(old.code(),input.code());
            db.update("UPDATE company SET name=?,timezone=?,active=?,lock_version=lock_version+1 WHERE id=?",input.name().trim(),input.timezone(),input.active(),id);
            var value=company(actor,id);return outcome(value,id,value.version(),List.of(id));
        });
    }
    public IdentityService.StoredResult createPlant(SessionUser actor,String key,PlantInput input) {
        return commands.execute(actor,"organization.manage","plant.create",key,input,()->policy.validateScope(input.companyId(),null,null),v->{},()->{
            unique("plant",input.code(),input.companyId());UUID id=UUID.randomUUID();
            db.update("INSERT INTO plant(id,deployment_id,code,name,company_id,active) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1",id,input.code(),input.name().trim(),input.companyId(),input.active());
            var value=plant(actor,id);return outcome(value,id,value.version(),List.of(value.companyId()));
        });
    }
    public IdentityService.StoredResult updatePlant(SessionUser actor,UUID id,String key,String etag,PlantInput input) {
        return commands.execute(actor,"organization.manage","plant.update:"+id,key,List.of(etag,input),()->plant(actor,id),v->{},()->{
            var old=plant(actor,id);IdentityCommands.match(old.version(),etag);codeUnchanged(old.code(),input.code());
            if(!old.companyId().equals(input.companyId())) throw new ApiProblem(409,"IMMUTABLE_ORGANIZATION_PARENT");
            db.update("UPDATE plant SET name=?,active=?,lock_version=lock_version+1 WHERE id=?",input.name().trim(),input.active(),id);
            var value=plant(actor,id);return outcome(value,id,value.version(),List.of(value.companyId()));
        });
    }
    private void validateCompanies(List<UUID> companies) {
        if(new HashSet<>(companies).size()!=companies.size()) throw new ApiProblem(422,"DUPLICATE_COMPANY_IDS");
        companies.forEach(id->policy.validateScope(id,null,null));
    }
    private void companyLinks(UUID org,List<UUID> companies) {
        db.update("UPDATE company_purch_org SET active=false WHERE purchasing_org_id=?",org);
        companies.forEach(id->db.update("INSERT INTO company_purch_org(deployment_id,company_id,purchasing_org_id,active) SELECT deployment_id,?,?,true FROM app_guard WHERE id=1 ON CONFLICT(deployment_id,company_id,purchasing_org_id) DO UPDATE SET active=true",id,org));
    }
    public IdentityService.StoredResult createPurchasing(SessionUser actor,String key,PurchasingInput input) {
        return commands.execute(actor,"organization.manage","purchasing-org.create",key,input,()->validateCompanies(input.companyIds()),v->{},()->{
            unique("purchasing_org",input.code(),null);UUID id=UUID.randomUUID();
            db.update("INSERT INTO purchasing_org(id,deployment_id,code,name,active) SELECT ?,deployment_id,?,?,? FROM app_guard WHERE id=1",id,input.code(),input.name().trim(),input.active());
            companyLinks(id,input.companyIds());var value=purchasingOrganization(actor,id);return outcome(value,id,value.version(),value.companyIds());
        });
    }
    public IdentityService.StoredResult updatePurchasing(SessionUser actor,UUID id,String key,String etag,PurchasingInput input) {
        return commands.execute(actor,"organization.manage","purchasing-org.update:"+id,key,List.of(etag,input),()->{purchasingOrganization(actor,id);validateCompanies(input.companyIds());},v->{},()->{
            var old=purchasingOrganization(actor,id);IdentityCommands.match(old.version(),etag);codeUnchanged(old.code(),input.code());
            db.update("UPDATE purchasing_org SET name=?,active=?,lock_version=lock_version+1 WHERE id=?",input.name().trim(),input.active(),id);
            companyLinks(id,input.companyIds());var value=purchasingOrganization(actor,id);var scopes=new HashSet<>(old.companyIds());scopes.addAll(input.companyIds());
            return outcome(value,id,value.version(),List.copyOf(scopes));
        });
    }
}
