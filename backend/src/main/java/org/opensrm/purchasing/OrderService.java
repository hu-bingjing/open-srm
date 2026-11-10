package org.opensrm.purchasing;
import java.util.*;
import org.opensrm.access.AccessPolicy;
import org.opensrm.identity.*;
import org.opensrm.foundation.ApiProblem;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OrderService {
    public record Schedule(UUID id,String externalSchedule,String orderedQuantity,String requestedDate,String effectiveQuantity,String pendingQuantity,String unconfirmedQuantity) { }
    public record Item(UUID id,String externalItem,UUID plantId,String plantCode,UUID materialId,String materialCode,String materialName,String unit,List<Schedule> schedules) { }
    public record Order(UUID id,String externalId,UUID companyId,String companyCode,UUID purchasingOrgId,String purchasingOrgCode,UUID supplierId,String supplierCode,String sourceRevision,long sourceSequence,String lifecycle,boolean consistencyHold,long version,List<Item> items) { }
    public record Revision(UUID id,UUID orderId,String sourceRevision,long sourceSequence,String lifecycle,String receivedAt,List<Item> items) { }
    private record Head(Order value,UUID revisionId) { }
    private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;
    public OrderService(JdbcTemplate db,AccessPolicy policy) { this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy; }
    private String lines(AccessPolicy.Context ctx,String revision) {
        return "i.revision_id="+revision+" AND EXISTS(SELECT 1 FROM plant ap WHERE ap.id=i.plant_id AND ap.active) AND "+policy.grant("o.company_id","i.plant_id","o.purchasing_org_id",AccessPolicy.Permission.ORDER);
    }
    public String visible(AccessPolicy.Context ctx) {
        return "o.deployment_id="+AccessPolicy.REALM+" AND "+policy.currentActor()
            +" AND EXISTS(SELECT 1 FROM company_purch_org cp JOIN purchasing_org op ON op.id=cp.purchasing_org_id WHERE cp.company_id=o.company_id AND cp.purchasing_org_id=o.purchasing_org_id AND cp.active AND op.active)"
            +(ctx.supplierUser()?" AND o.supplier_id=:boundSupplier AND EXISTS(SELECT 1 FROM supplier_company sc WHERE sc.supplier_id=o.supplier_id AND sc.company_id=o.company_id AND sc.active)":"")
            +" AND EXISTS(SELECT 1 FROM po_item i WHERE "+lines(ctx,"o.current_revision_id")+")";
    }
    private static final String FROM="purchase_order o JOIN company c ON c.id=o.company_id JOIN purchasing_org p ON p.id=o.purchasing_org_id JOIN supplier s ON s.id=o.supplier_id";
    private static final RowMapper<Head> HEAD=(r,n)->new Head(new Order(r.getObject("id",UUID.class),r.getString("external_id"),r.getObject("company_id",UUID.class),r.getString("company_code"),r.getObject("purchasing_org_id",UUID.class),r.getString("porg_code"),r.getObject("supplier_id",UUID.class),r.getString("supplier_code"),r.getString("source_revision"),r.getLong("source_sequence"),r.getString("lifecycle"),r.getBoolean("consistency_hold"),r.getLong("lock_version"),List.of()),r.getObject("current_revision_id",UUID.class));
    private static final String SELECT="SELECT o.*,c.code company_code,p.code porg_code,s.code supplier_code FROM ";
    private Order enrich(Head h,AccessPolicy.Context ctx,UUID plantFilter) {
        Order v=h.value();return new Order(v.id(),v.externalId(),v.companyId(),v.companyCode(),v.purchasingOrgId(),v.purchasingOrgCode(),v.supplierId(),v.supplierCode(),v.sourceRevision(),v.sourceSequence(),v.lifecycle(),v.consistencyHold(),v.version(),items(ctx,v.id(),h.revisionId(),plantFilter));
    }
    private List<Item> items(AccessPolicy.Context ctx,UUID order,UUID revision,UUID plantFilter) {
        var args=policy.parameters(ctx).addValue("order",order).addValue("revision",revision).addValue("plant",plantFilter);
        var rows=sql.query("SELECT i.*,p.code plant_code,m.external_key material_code,m.name material_name FROM purchase_order o JOIN po_item i ON i.revision_id=:revision JOIN plant p ON p.id=i.plant_id JOIN material m ON m.id=i.material_id WHERE o.id=:order AND "+visible(ctx)+" AND "+lines(ctx,":revision")+(plantFilter==null?"":" AND i.plant_id=:plant")+" ORDER BY i.external_item",args,(r,n)->new Item(r.getObject("id",UUID.class),r.getString("external_item"),r.getObject("plant_id",UUID.class),r.getString("plant_code"),r.getObject("material_id",UUID.class),r.getString("material_code"),r.getString("material_name"),r.getString("unit"),List.of()));
        return rows.stream().map(i->new Item(i.id(),i.externalItem(),i.plantId(),i.plantCode(),i.materialId(),i.materialCode(),i.materialName(),i.unit(),sql.query("SELECT s.*,(SELECT COALESCE(sum(c.quantity),0) FROM confirmation c WHERE c.schedule_id=s.id AND c.status='EFFECTIVE') effective_quantity,(SELECT COALESCE(sum(c.quantity),0) FROM confirmation c WHERE c.schedule_id=s.id AND c.status='SUBMITTED') pending_quantity FROM po_schedule s WHERE s.item_id=:item ORDER BY s.external_schedule",new MapSqlParameterSource("item",i.id()),(r,n)->new Schedule(r.getObject("id",UUID.class),r.getString("external_schedule"),r.getBigDecimal("ordered_quantity").toPlainString(),r.getDate("requested_date").toString(),r.getBigDecimal("effective_quantity").toPlainString(),r.getBigDecimal("pending_quantity").toPlainString(),r.getBigDecimal("ordered_quantity").subtract(r.getBigDecimal("effective_quantity")).subtract(r.getBigDecimal("pending_quantity")).toPlainString())))).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IdentityService.Page<Order> list(SessionUser actor,int page,int size,String query,UUID company,UUID plant,UUID purchasing,UUID supplier) {
        var ctx=policy.context(actor,"order.read");var args=policy.parameters(ctx).addValue("query",AccessPolicy.search(query)).addValue("limit",size).addValue("offset",(long)page*size);
        String where=visible(ctx)+" AND lower(o.external_id) LIKE :query";
        if(company!=null) {args.addValue("company",company);where+=" AND o.company_id=:company";}
        if(purchasing!=null) {args.addValue("porg",purchasing);where+=" AND o.purchasing_org_id=:porg";}
        if(supplier!=null) {args.addValue("supplier",supplier);where+=" AND o.supplier_id=:supplier";}
        if(plant!=null) {args.addValue("plant",plant);where+=" AND EXISTS(SELECT 1 FROM po_item i WHERE "+lines(ctx,"o.current_revision_id")+" AND i.plant_id=:plant)";}
        var heads=sql.query(SELECT+FROM+" WHERE "+where+" ORDER BY o.created_at,o.id LIMIT :limit OFFSET :offset",args,HEAD);
        return new IdentityService.Page<>(heads.stream().map(h->enrich(h,ctx,plant)).toList(),sql.queryForObject("SELECT count(*) FROM "+FROM+" WHERE "+where,args,Long.class),page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Order get(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"order.read");var rows=sql.query(SELECT+FROM+" WHERE o.id=:id AND "+visible(ctx),policy.parameters(ctx).addValue("id",id),HEAD);
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return enrich(rows.getFirst(),ctx,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IdentityService.Page<Revision> revisions(SessionUser actor,UUID id,int page,int size) {
        get(actor,id);var ctx=policy.context(actor,"order.read");var args=policy.parameters(ctx).addValue("id",id).addValue("limit",size).addValue("offset",(long)page*size);
        String where="o.id=:id AND "+visible(ctx)+" AND EXISTS(SELECT 1 FROM po_item i WHERE "+lines(ctx,"r.id")+")";
        var rows=sql.query("SELECT r.* FROM po_revision r JOIN purchase_order o ON o.id=r.order_id WHERE "+where+" ORDER BY r.source_sequence DESC LIMIT :limit OFFSET :offset",args,(r,n)->new Revision(r.getObject("id",UUID.class),id,r.getString("source_revision"),r.getLong("source_sequence"),r.getString("lifecycle"),r.getTimestamp("received_at").toInstant().toString(),List.of()));
        return new IdentityService.Page<>(rows.stream().map(r->new Revision(r.id(),id,r.sourceRevision(),r.sourceSequence(),r.lifecycle(),r.receivedAt(),items(ctx,id,r.id(),null))).toList(),sql.queryForObject("SELECT count(*) FROM po_revision r JOIN purchase_order o ON o.id=r.order_id WHERE "+where,args,Long.class),page,size);
    }
}
