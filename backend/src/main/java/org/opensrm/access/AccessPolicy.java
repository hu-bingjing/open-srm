package org.opensrm.access;

import java.util.*;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.identity.IdentityCommands;
import org.opensrm.identity.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;

/** SQL predicates are shared by list, count, detail and mutation guards. SQL expressions are trusted code, never user input. */
@Component
public class AccessPolicy {
    public static final String REALM="(SELECT deployment_id FROM app_guard WHERE id=1)";
    public record Context(SessionUser actor,Set<String> permissions,boolean supplierUser,UUID supplierId) { }
    public enum Permission { CATALOG, SUPPLIER_READ, SUPPLIER_MANAGE, AUDIT, ORDER, SYNC, CONFIRM, APPROVE, ASN }
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate sql;
    private final IdentityCommands commands;
    public AccessPolicy(JdbcTemplate db,IdentityCommands commands) { this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.commands=commands; }
    public Context context(SessionUser actor,String permission) {
        commands.require(actor,permission);
        var permissions=new HashSet<>(db.queryForList("SELECT DISTINCT p.permission FROM user_role r JOIN role_permission p ON r.role_code=p.role_code WHERE r.user_id=?",String.class,actor.id()));
        boolean external=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role WHERE user_id=? AND role_code='SUPPLIER')",Boolean.class,actor.id()));
        var bindings=db.queryForList("SELECT supplier_id FROM user_supplier WHERE user_id=?",UUID.class,actor.id());
        UUID binding=bindings.isEmpty()?null:bindings.getFirst();
        if (!external && binding!=null) throw new ApiProblem(403,"INCONSISTENT_IDENTITY");
        return new Context(actor,Set.copyOf(permissions),external,binding);
    }
    public MapSqlParameterSource parameters(Context ctx) {
        return new MapSqlParameterSource().addValue("actor",ctx.actor().id()).addValue("sessionVersion",ctx.actor().sessionVersion()).addValue("boundSupplier",ctx.supplierId());
    }
    public String currentActor() {
        return "EXISTS(SELECT 1 FROM app_user au WHERE au.id=:actor AND au.status='ACTIVE' AND au.session_version=:sessionVersion)";
    }
    public String effectiveGrant() {
        return "g.user_id=:actor AND g.active AND g.deployment_id="+REALM
            +" AND EXISTS(SELECT 1 FROM company gc WHERE gc.id=g.company_id AND gc.deployment_id=g.deployment_id AND gc.active)"
            +" AND (g.plant_id IS NULL OR EXISTS(SELECT 1 FROM plant gp WHERE gp.id=g.plant_id AND gp.company_id=g.company_id AND gp.active))"
            +" AND (g.purchasing_org_id IS NULL OR EXISTS(SELECT 1 FROM company_purch_org gj JOIN purchasing_org go ON go.id=gj.purchasing_org_id WHERE gj.company_id=g.company_id AND gj.purchasing_org_id=g.purchasing_org_id AND gj.active AND go.active))";
    }
    public String grant(String company,String plant,String purchasingOrg,Permission permission) {
        String values=switch(permission) {
            case CATALOG -> "'organization.read','supplier.read','supplier.manage','audit.read','order.read','sync.manage','confirmation.submit','delivery.approve','asn.manage'";
            case SUPPLIER_READ -> "'supplier.read','supplier.manage'";
            case SUPPLIER_MANAGE -> "'supplier.manage'";
            case AUDIT -> "'audit.read'";
            case ORDER -> "'order.read'";
            case SYNC -> "'sync.manage'";
            case CONFIRM -> "'confirmation.submit'";
            case ASN -> "'asn.manage'";
            case APPROVE -> "'delivery.approve'";
        };
        return "EXISTS(SELECT 1 FROM organization_grant g WHERE "+effectiveGrant()+" AND g.permission IN ("+values+") AND g.company_id="+company
            +(plant==null?"":" AND (g.plant_id IS NULL OR g.plant_id="+plant+")")
            +(purchasingOrg==null?"":" AND (g.purchasing_org_id IS NULL OR g.purchasing_org_id="+purchasingOrg+")")+")";
    }
    public String catalog(Context ctx,String company,String plant,String purchasingOrg) {
        if (ctx.permissions().contains("organization.manage")) return currentActor();
        return currentActor()+" AND "+grant(company,plant,purchasingOrg,Permission.CATALOG)+supplierCompanyRestriction(ctx,company);
    }
    private String supplierCompanyRestriction(Context ctx,String company) {
        return ctx.supplierUser()?" AND EXISTS(SELECT 1 FROM supplier_company bs WHERE bs.company_id="+company+" AND bs.supplier_id=:boundSupplier AND bs.active AND bs.deployment_id="+REALM+")":"";
    }
    public String companyRead(Context ctx,String company) {
        return currentActor()+" AND "+grant(company,"NULL","NULL",Permission.SUPPLIER_READ)+supplierCompanyRestriction(ctx,company);
    }
    public String supplierVisible(Context ctx,String supplier,String companyParameter) {
        return currentActor()+(ctx.supplierUser()?" AND "+supplier+"=:boundSupplier":"")
            +" AND EXISTS(SELECT 1 FROM supplier_company sc JOIN company vc ON vc.id=sc.company_id WHERE sc.supplier_id="+supplier
            +" AND sc.deployment_id="+REALM+" AND vc.active"+(companyParameter==null?"":" AND sc.company_id="+companyParameter)
            +(ctx.supplierUser()?" AND sc.active":"")+" AND "+grant("sc.company_id","NULL","NULL",Permission.SUPPLIER_READ)+")";
    }
    public void requireCompanyManage(Context ctx,UUID company) {
        var args=parameters(ctx).addValue("company",company);
        boolean permitted=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM company c WHERE c.id=:company AND c.deployment_id="+REALM+" AND c.active AND "+currentActor()+" AND "+grant("c.id","NULL","NULL",Permission.SUPPLIER_MANAGE)+")",args,Boolean.class));
        if (!permitted || ctx.supplierUser()) throw new ApiProblem(404,"NOT_FOUND");
    }
    public void requireSupplierRead(Context ctx,UUID supplier) {
        boolean permitted=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM supplier s WHERE s.id=:supplier AND s.deployment_id="+REALM+" AND "+supplierVisible(ctx,"s.id",null)+")",parameters(ctx).addValue("supplier",supplier),Boolean.class));
        if (!permitted) throw new ApiProblem(404,"NOT_FOUND");
    }
    public boolean canEditSupplier(Context ctx,UUID supplier) {
        if (ctx.supplierUser() || !ctx.permissions().contains("supplier.manage")) return false;
        return Boolean.TRUE.equals(sql.queryForObject("SELECT "+currentActor()+" AND EXISTS(SELECT 1 FROM supplier_company sc WHERE sc.supplier_id=:supplier) AND NOT EXISTS(SELECT 1 FROM supplier_company sc WHERE sc.supplier_id=:supplier AND NOT ("+grant("sc.company_id","NULL","NULL",Permission.SUPPLIER_MANAGE)+"))",parameters(ctx).addValue("supplier",supplier),Boolean.class));
    }
    public void requireFullSupplierManage(Context ctx,UUID supplier) {
        requireSupplierRead(ctx,supplier);
        if (!canEditSupplier(ctx,supplier)) throw new ApiProblem(404,"NOT_FOUND");
    }
    public void validateScope(UUID company,UUID plant,UUID purchasingOrg) {
        boolean valid=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM company WHERE id=? AND active AND deployment_id="+REALM+")",Boolean.class,company));
        if (plant!=null) valid &= Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM plant WHERE id=? AND company_id=? AND active)",Boolean.class,plant,company));
        if (purchasingOrg!=null) valid &= Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM company_purch_org co JOIN purchasing_org po ON po.id=co.purchasing_org_id WHERE co.company_id=? AND po.id=? AND co.active AND po.active)",Boolean.class,company,purchasingOrg));
        if (!valid) throw new ApiProblem(404,"NOT_FOUND");
    }
    public static String search(String query) {
        return "%"+query.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
}
