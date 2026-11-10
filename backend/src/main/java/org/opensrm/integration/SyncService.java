package org.opensrm.integration;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import tools.jackson.databind.ObjectMapper;
import org.opensrm.access.AccessPolicy;
import org.opensrm.foundation.*;
import org.opensrm.collaboration.CollaborationLifecycle;
import org.opensrm.shipping.ReceiptImporter;
import org.opensrm.identity.*;
import org.opensrm.integration.port.*;
import org.opensrm.integration.port.ErpData.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {
    public record ConnectionInput(@NotBlank @Size(max=160) String name,@NotNull UUID companyId,@NotNull UUID purchasingOrgId,@NotNull UUID supplierId,@NotEmpty @Size(max=20) List<@NotNull UUID> plantIds, @Pattern(regexp="mock|sap-odata") String adapterType, @Size(max=40) String sourceSystem, @Size(max=40) String sourceClient, @Size(max=80) String companyExternalKey, @Size(max=80) String purchasingOrgExternalKey, @Size(max=80) String supplierExternalKey, @Size(max=20) Map<@Size(max=40) String,UUID> plantMappings) { }
    public record Connection(UUID id,String name,String adapterType,UUID companyId,UUID purchasingOrgId,UUID supplierId,List<UUID> plantIds,boolean active,long version) { }
    public record Start(@NotNull UUID connectionId,@NotNull @Pattern(regexp="PURCHASE_ORDER|GOODS_RECEIPT|METADATA") String entityType,@NotNull @Pattern(regexp="INITIAL|UPDATE|DUPLICATE|OUT_OF_ORDER|CONFLICT|CANCEL|CLOSE|REOPEN|REMOVE_LINE|INVALID_MAPPING|FAIL_ONCE|PERMANENT_FAIL|CONFIRMATION|GR_INITIAL|GR_FAIL_ONCE|GR_PARTIAL_REVERSE|GR_FULL_REVERSE|GR_CONFLICT|GR_OVER|GR_UNMATCHED|SAP_READ|SAP_RECEIPTS|SAP_METADATA") String scenario) { }
    public record Job(UUID id,UUID connectionId,String entityType,String scenario,String status,int processed,int applied,int skipped,int quarantined,int attempts,String errorCode,String nextCursor,String snapshotId,long version,String createdAt,String updatedAt) { }
    public record Issue(UUID id,String externalKey,String sourceRevision,String errorCode,String createdAt) { }
    private final JdbcTemplate db;private final NamedParameterJdbcTemplate sql;private final AccessPolicy policy;private final IdentityCommands commands;private final Map<String,ErpAdapter> adapters;private final ObjectMapper json;private final CollaborationLifecycle collaboration;private final ReceiptImporter receiptImporter;
    public SyncService(JdbcTemplate db,AccessPolicy policy,IdentityCommands commands,List<ErpAdapter> providers,ObjectMapper json,CollaborationLifecycle collaboration,ReceiptImporter receiptImporter) {
        this.db=db;this.sql=new NamedParameterJdbcTemplate(db);this.policy=policy;this.commands=commands;this.adapters=new HashMap<>();for(var a:providers)this.adapters.put(a.type(),a);this.json=json;this.collaboration=collaboration;this.receiptImporter=receiptImporter;
    }
    private ErpAdapter adapter(Connection c) {var a=adapters.get(c.adapterType());if(a==null||!a.configured())throw new ErpReadFailure("SAP_NOT_CONFIGURED");return a;}
    private Request request(Connection c,String scenario,String cursor,int size,int attempt,String snapshot) {
        var v=db.queryForMap("SELECT c.*,co.code local_company,p.code local_purchasing_org,s.code local_supplier FROM erp_connection c JOIN company co ON co.id=c.company_id JOIN purchasing_org p ON p.id=c.purchasing_org_id JOIN supplier s ON s.id=c.supplier_id WHERE c.id=?",c.id());
        var localPlants=new HashMap<String,String>();db.query("SELECT m.external_key,p.code FROM erp_plant_mapping m JOIN plant p ON p.id=m.plant_id WHERE m.connection_id=?",(RowCallbackHandler)row->localPlants.put(row.getString(1),row.getString(2)),c.id());
        var scope=new ReadScope((String)v.get("source_system"),(String)v.get("source_client"),(String)v.get("company_external_key"),(String)v.get("purchasing_org_external_key"),(String)v.get("supplier_external_key"),db.queryForList("SELECT external_key FROM erp_plant_mapping WHERE connection_id=? ORDER BY external_key",String.class,c.id()),(String)v.get("local_company"),(String)v.get("local_purchasing_org"),(String)v.get("local_supplier"),localPlants);
        return new Request(scenario,cursor,size,attempt,c.plantIds().size(),scope,snapshot);
    }
    public Object capabilities(SessionUser actor) {commands.require(actor,"sync.read");return adapters.values().stream().map(a->Map.of("adapterType",a.type(),"configured",a.configured(),"writeEnabled",false)).toList();}

    private String connectionScope(AccessPolicy.Context ctx) { return "c.deployment_id="+AccessPolicy.REALM+" AND c.owner_id=:actor AND "+policy.currentActor()+" AND "+policy.grant("c.company_id","NULL","NULL",AccessPolicy.Permission.SYNC); }
    private static final RowMapper<Connection> CONNECTION=(r,n)->new Connection(r.getObject("id",UUID.class),r.getString("name"),r.getString("adapter_type"),r.getObject("company_id",UUID.class),r.getObject("purchasing_org_id",UUID.class),r.getObject("supplier_id",UUID.class),List.of(),r.getBoolean("active"),r.getLong("lock_version"));
    private static final RowMapper<Job> JOB=(r,n)->new Job(r.getObject("id",UUID.class),r.getObject("connection_id",UUID.class),r.getString("entity_type"),r.getString("scenario"),r.getString("status"),r.getInt("processed"),r.getInt("applied"),r.getInt("skipped"),r.getInt("quarantined"),r.getInt("attempts"),r.getString("error_code"),r.getString("next_cursor"),r.getString("snapshot_id"),r.getLong("lock_version"),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("updated_at").toInstant().toString());
    private Connection plants(Connection c) { return new Connection(c.id(),c.name(),c.adapterType(),c.companyId(),c.purchasingOrgId(),c.supplierId(),db.queryForList("SELECT plant_id FROM erp_plant_mapping WHERE connection_id=? ORDER BY external_key",UUID.class,c.id()),c.active(),c.version()); }
    public IdentityService.Page<Connection> connections(SessionUser actor,int page,int size) {
        var ctx=policy.context(actor,"sync.read");var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);String where=connectionScope(ctx);
        var rows=sql.query("SELECT c.* FROM erp_connection c WHERE "+where+" ORDER BY c.created_at,c.id LIMIT :limit OFFSET :offset",args,CONNECTION);
        return new IdentityService.Page<>(rows.stream().map(this::plants).toList(),sql.queryForObject("SELECT count(*) FROM erp_connection c WHERE "+where,args,Long.class),page,size);
    }
    public Connection connection(SessionUser actor,UUID id) {
        var ctx=policy.context(actor,"sync.read");var rows=sql.query("SELECT c.* FROM erp_connection c WHERE c.id=:id AND "+connectionScope(ctx),policy.parameters(ctx).addValue("id",id),CONNECTION);
        if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return plants(rows.getFirst());
    }
    private void companySync(SessionUser actor,UUID company) {
        var ctx=policy.context(actor,"sync.manage");
        if(!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM company c WHERE c.id=:company AND c.active AND "+policy.currentActor()+" AND "+policy.grant("c.id","NULL","NULL",AccessPolicy.Permission.SYNC)+")",policy.parameters(ctx).addValue("company",company),Boolean.class))) throw new ApiProblem(404,"NOT_FOUND");
    }
    public IdentityService.StoredResult create(SessionUser actor,String key,ConnectionInput input) {
        return commands.execute(actor,"sync.manage","connection.create",key,input,()->{
            companySync(actor,input.companyId());policy.requireCompanyManage(policy.context(actor,"supplier.manage"),input.companyId());policy.requireSupplierRead(policy.context(actor,"supplier.read"),input.supplierId());
            if(new HashSet<>(input.plantIds()).size()!=input.plantIds().size()) throw new ApiProblem(422,"DUPLICATE_PLANT");
            for(UUID plant:input.plantIds()) policy.validateScope(input.companyId(),plant,input.purchasingOrgId());
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM supplier_company WHERE supplier_id=? AND company_id=? AND active)",Boolean.class,input.supplierId(),input.companyId()))) throw new ApiProblem(404,"NOT_FOUND");
        },body->connection(actor,UUID.fromString(json.readTree(body).get("id").asString())),()->{
            UUID id=UUID.randomUUID();db.update("INSERT INTO erp_connection(id,deployment_id,owner_id,name,adapter_type,company_id,purchasing_org_id,supplier_id) SELECT ?,deployment_id,?,?,'mock',?,?,? FROM app_guard WHERE id=1",id,actor.id(),input.name().trim(),input.companyId(),input.purchasingOrgId(),input.supplierId());
            if(input.adapterType()!=null&&!input.adapterType().equals("mock")) {
                if(java.util.stream.Stream.of(input.sourceSystem(),input.sourceClient(),input.companyExternalKey(),input.purchasingOrgExternalKey(),input.supplierExternalKey()).anyMatch(v->v==null||v.isBlank()||v.equals("*"))||input.plantMappings()==null||!new HashSet<>(input.plantMappings().values()).equals(new HashSet<>(input.plantIds()))||input.plantMappings().size()!=input.plantIds().size()||input.plantMappings().keySet().stream().anyMatch(v->v==null||v.isBlank()||v.equals("*")))throw new ApiProblem(422,"SOURCE_MAPPING_REQUIRED");
                db.update("UPDATE erp_connection SET adapter_type='sap-odata',source_system=?,source_client=?,company_external_key=?,purchasing_org_external_key=?,supplier_external_key=? WHERE id=?",input.sourceSystem(),input.sourceClient(),input.companyExternalKey(),input.purchasingOrgExternalKey(),input.supplierExternalKey(),id);
                for(var mapping:input.plantMappings().entrySet())db.update("INSERT INTO erp_plant_mapping(connection_id,deployment_id,company_id,external_key,plant_id) SELECT ?,deployment_id,?,?,? FROM app_guard WHERE id=1",id,input.companyId(),mapping.getKey(),mapping.getValue());
            } else
            for(int n=0;n<input.plantIds().size();n++) db.update("INSERT INTO erp_plant_mapping(connection_id,deployment_id,company_id,external_key,plant_id) SELECT ?,deployment_id,?,?,? FROM app_guard WHERE id=1",id,input.companyId(),"PLANT-"+(n+1),input.plantIds().get(n));
            return new IdentityCommands.Outcome(connection(actor,id),id,0,"SYNC",null,List.of(input.companyId()));
        });
    }
    private void runnable(SessionUser actor,UUID id) { var c=connection(actor,id);commands.require(actor,"sync.manage");if(!c.active()) throw new ApiProblem(409,"CONNECTION_DISABLED"); }
    private Job rawJob(UUID id) { var rows=db.query("SELECT * FROM sync_job WHERE id=?",JOB,id);if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND");return rows.getFirst(); }
    public Job job(SessionUser actor,UUID id) { var j=rawJob(id);connection(actor,j.connectionId());return j; }
    public IdentityService.Page<Job> jobs(SessionUser actor,int page,int size) {
        var ctx=policy.context(actor,"sync.read");var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);String where=connectionScope(ctx);
        return new IdentityService.Page<>(sql.query("SELECT j.* FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE "+where+" ORDER BY j.created_at DESC,j.id LIMIT :limit OFFSET :offset",args,JOB),sql.queryForObject("SELECT count(*) FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE "+where,args,Long.class),page,size);
    }
    public IdentityService.Page<Issue> issues(SessionUser actor,UUID id,int page,int size) {
        job(actor,id);return new IdentityService.Page<>(db.query("SELECT * FROM sync_issue WHERE job_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",(r,n)->new Issue(r.getObject("id",UUID.class),r.getString("external_key"),r.getString("source_revision"),r.getString("error_code"),r.getTimestamp("created_at").toInstant().toString()),id,size,(long)page*size),db.queryForObject("SELECT count(*) FROM sync_issue WHERE job_id=?",Long.class,id),page,size);
    }
    public IdentityService.StoredResult start(SessionUser actor,String key,Start input) {
        return commands.execute(actor,"sync.manage","sync.start",key,input,()->runnable(actor,input.connectionId()),v->{},()->{
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sync_job WHERE connection_id=? AND status IN ('PENDING','RUNNING'))",Boolean.class,input.connectionId()))) throw new ApiProblem(409,"SYNC_ALREADY_RUNNING");
            var connection=connection(actor,input.connectionId());ErpAdapter provider;try {provider=adapter(connection);}catch(ErpReadFailure e){throw new ApiProblem(503,e.code());}String snapshot;
            if(provider.liveRead()) {
                String expected=provider.readScenario(input.entityType());
                if(!expected.equals(input.scenario()))throw new ApiProblem(422,"SYNC_SCENARIO_MISMATCH");
                snapshot=provider.newReadBatch();
            } else {
                if(input.entityType().equals("METADATA")||input.scenario().startsWith("SAP_")||input.entityType().equals("GOODS_RECEIPT")!=input.scenario().startsWith("GR_"))throw new ApiProblem(422,"SYNC_SCENARIO_MISMATCH");
                snapshot=input.entityType().equals("GOODS_RECEIPT")?provider.fetchGoodsReceipts(request(connection,input.scenario(),null,1,0,null)).snapshotId():provider.openSnapshot(request(connection,input.scenario(),null,0,0,null));
            }
            UUID id=UUID.randomUUID();db.update("INSERT INTO sync_job(id,deployment_id,connection_id,actor_id,actor_session_version,request_id,scenario,snapshot_id,status) SELECT ?,deployment_id,?,?,?,?,?,?,'PENDING' FROM app_guard WHERE id=1",id,input.connectionId(),actor.id(),actor.sessionVersion(),RequestIds.current(),input.scenario(),snapshot);
            db.update("UPDATE sync_job SET entity_type=? WHERE id=?",input.entityType(),id);var c=connection(actor,input.connectionId());return new IdentityCommands.Outcome(rawJob(id),id,0,"SYNC",null,List.of(c.companyId()));
        });
    }
    public IdentityService.StoredResult retry(SessionUser actor,UUID id,String key,String etag) {
        return commands.execute(actor,"sync.manage","sync.retry:"+id,key,etag,()->runnable(actor,job(actor,id).connectionId()),v->{},()->{
            var j=rawJob(id);IdentityCommands.match(j.version(),etag);
            if(!j.status().equals("FAILED") || j.attempts()>=5) throw new ApiProblem(409,"RETRY_NOT_ALLOWED");
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sync_job WHERE connection_id=? AND status IN ('PENDING','RUNNING'))",Boolean.class,j.connectionId()))) throw new ApiProblem(409,"SYNC_ALREADY_RUNNING");
            db.update("UPDATE sync_job SET status='PENDING',attempts=attempts+1,next_attempt_at=CURRENT_TIMESTAMP,error_code=NULL,actor_id=?,actor_session_version=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",actor.id(),actor.sessionVersion(),id);
            return new IdentityCommands.Outcome(rawJob(id),id,j.version()+1,"SYNC",null,List.of(connection(actor,j.connectionId()).companyId()));
        });
    }
    private String hash(PurchaseOrder value) {
        var canonicalItems=value.items().stream().sorted(Comparator.comparing(Item::externalItem)).map(i->new Item(i.externalItem(),i.plantKey(),i.materialKey(),i.unit(),i.schedules().stream().sorted(Comparator.comparing(Schedule::externalSchedule)).map(s->new Schedule(s.externalSchedule(),s.orderedQuantity().stripTrailingZeros(),s.requestedDate())).toList())).toList();
        var canonical=new PurchaseOrder(value.source(),value.companyKey(),value.purchasingOrgKey(),value.supplierKey(),value.lifecycle(),canonicalItems);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void audit(UUID actor,UUID request,String operation,UUID object,UUID company,UUID supplier,String category) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO audit_event(id,deployment_id,actor_id,operation,object_id,request_id,category,supplier_id) SELECT ?,deployment_id,?,?,?,?,?,? FROM app_guard WHERE id=1",id,actor,operation,object,request,category,supplier);
        db.update("INSERT INTO audit_event_company(deployment_id,event_id,company_id) SELECT deployment_id,?,? FROM app_guard WHERE id=1",id,company);
    }
    private int quarantine(Job job,PurchaseOrder order,String digest,String code,UUID actor,UUID request,Connection c,boolean hold) {
        var ids=db.queryForList("SELECT id FROM purchase_order WHERE connection_id=? AND external_id=?",UUID.class,c.id(),order.source().externalKey());
        if(hold && !ids.isEmpty()) {db.update("UPDATE purchase_order SET consistency_hold=true,lock_version=lock_version+1 WHERE id=? AND NOT consistency_hold",ids.getFirst());collaboration.freeze(ids.getFirst());}
        UUID issue=UUID.randomUUID();int changed=db.update("INSERT INTO sync_issue(id,connection_id,job_id,external_key,source_revision,content_hash,error_code) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",issue,c.id(),job.id(),order.source().externalKey(),order.source().sourceRevision(),digest,code);
        if(changed>0) audit(actor,request,"sync.quarantine",issue,c.companyId(),null,"SYNC");return 2;
    }
    private String validate(Connection c,PurchaseOrder value,Map<String,UUID> plants,Map<String,UUID> materialIds,Map<String,String> units,Map<String,Object> config) {
        if(!value.source().sourceSystem().equals(config.get("source_system")) || !value.source().sourceClient().equals(config.get("source_client")) || value.source().sourceSequence()<1) return "INVALID_SOURCE";
        if(!value.companyKey().equals(config.get("company_external_key")) || !value.purchasingOrgKey().equals(config.get("purchasing_org_external_key")) || !value.supplierKey().equals(config.get("supplier_external_key"))) return "ORGANIZATION_MAPPING_CHANGED";
        if(!Set.of("ACTIVE","CLOSED","CANCELLED").contains(value.lifecycle()) || value.items().isEmpty()) return "INVALID_ORDER";
        var keys=new HashSet<String>();
        for(var item:value.items()) {
            if(!keys.add(item.externalItem()) || !plants.containsKey(item.plantKey()) || !materialIds.containsKey(item.materialKey()) || !item.unit().equals(units.get(item.materialKey())) || item.schedules().isEmpty()) return "INVALID_LINE_MAPPING";
            var schedules=new HashSet<String>();
            for(var schedule:item.schedules()) {BigDecimal q=schedule.orderedQuantity();if(!schedules.add(schedule.externalSchedule()) || q==null || q.signum()<=0 || q.scale()>6 || q.precision()-q.scale()>13 || schedule.requestedDate()==null) return "INVALID_SCHEDULE";}
        }
        return null;
    }
    private int apply(Job job,PurchaseOrder value,Connection c,Map<String,Object> config,Map<String,UUID> plants,Map<String,UUID> materialIds,Map<String,String> units,UUID actor,UUID request) {
        String digest=hash(value);var source=value.source();
        String invalid=validate(c,value,plants,materialIds,units,config);
        if(invalid!=null) return quarantine(job,value,digest,invalid,actor,request,c,invalid.equals("ORGANIZATION_MAPPING_CHANGED"));
        var inbox=db.queryForList("SELECT content_hash FROM inbox_event WHERE connection_id=? AND external_key=? AND source_revision=?",String.class,c.id(),source.externalKey(),source.sourceRevision());
        if(!inbox.isEmpty()) return inbox.getFirst().equals(digest)?1:quarantine(job,value,digest,"SOURCE_REVISION_CONFLICT",actor,request,c,true);
        var existing=db.queryForList("SELECT * FROM purchase_order WHERE connection_id=? AND external_id=?",c.id(),source.externalKey());
        UUID orderId=existing.isEmpty()?UUID.randomUUID():(UUID)existing.getFirst().get("id");
        if(!existing.isEmpty()) {
            var old=existing.getFirst();long sequence=((Number)old.get("source_sequence")).longValue();
            if(source.sourceSequence()<=sequence) return quarantine(job,value,digest,"OUT_OF_ORDER_REVISION",actor,request,c,false);
            if(old.get("lifecycle").equals("CANCELLED") && !value.lifecycle().equals("CANCELLED")) return quarantine(job,value,digest,"CANCELLED_REOPEN_REQUIRES_RECONCILIATION",actor,request,c,true);
        } else db.update("INSERT INTO purchase_order(id,deployment_id,connection_id,external_id,company_id,purchasing_org_id,supplier_id,source_revision,source_sequence,lifecycle) SELECT ?,deployment_id,?,?,?,?,?,?,?,? FROM app_guard WHERE id=1",orderId,c.id(),source.externalKey(),c.companyId(),c.purchasingOrgId(),c.supplierId(),source.sourceRevision(),source.sourceSequence(),value.lifecycle());
        if(!existing.isEmpty()) collaboration.supersede(orderId,actor,request,source.sourceRevision());
        UUID revision=UUID.randomUUID();db.update("INSERT INTO po_revision(id,deployment_id,order_id,source_revision,source_sequence,content_hash,lifecycle,source_updated_at) SELECT ?,deployment_id,?,?,?,?,?,? FROM app_guard WHERE id=1",revision,orderId,source.sourceRevision(),source.sourceSequence(),digest,value.lifecycle(),java.sql.Timestamp.from(source.sourceUpdatedAt()));
        for(var item:value.items()) {
            UUID itemId=UUID.randomUUID();db.update("INSERT INTO po_item(id,deployment_id,revision_id,company_id,external_item,plant_id,material_id,unit) SELECT ?,deployment_id,?,?,?,?,?,? FROM app_guard WHERE id=1",itemId,revision,c.companyId(),item.externalItem(),plants.get(item.plantKey()),materialIds.get(item.materialKey()),item.unit());
            for(var schedule:item.schedules()) db.update("INSERT INTO po_schedule(id,deployment_id,item_id,external_schedule,ordered_quantity,requested_date) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1",UUID.randomUUID(),itemId,schedule.externalSchedule(),schedule.orderedQuantity(),schedule.requestedDate());
        }
        db.update("UPDATE purchase_order SET current_revision_id=?,source_revision=?,source_sequence=?,lifecycle=?,lock_version=lock_version+1 WHERE id=?",revision,source.sourceRevision(),source.sourceSequence(),value.lifecycle(),orderId);
        db.update("INSERT INTO inbox_event(connection_id,external_key,source_revision,content_hash) VALUES (?,?,?,?)",c.id(),source.externalKey(),source.sourceRevision(),digest);
        audit(actor,request,existing.isEmpty()?"order.import":"order.revise",orderId,c.companyId(),c.supplierId(),"ORDER");return 0;
    }
    @Transactional
    public void tick() {
        db.queryForObject("SELECT id FROM app_guard WHERE id=1 FOR UPDATE",Integer.class);
        var ids=db.queryForList("SELECT id FROM sync_job WHERE status IN ('PENDING','RUNNING') AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 1",UUID.class);if(ids.isEmpty()) return;
        UUID id=ids.getFirst();var row=db.queryForMap("SELECT * FROM sync_job WHERE id=?",id);var job=rawJob(id);UUID actor=(UUID)row.get("actor_id"),request=(UUID)row.get("request_id");
        Connection c;
        try {
            var user=new SessionUser(actor,"sync-worker",((Number)row.get("actor_session_version")).longValue());commands.require(user,"sync.manage");c=connection(user,job.connectionId());runnable(user,c.id());
            for(UUID plant:c.plantIds()) policy.validateScope(c.companyId(),plant,c.purchasingOrgId());
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM supplier_company WHERE supplier_id=? AND company_id=? AND active)",Boolean.class,c.supplierId(),c.companyId()))) throw new ApiProblem(404,"NOT_FOUND");
        } catch(ApiProblem denied) {fail(id,"AUTHORIZATION_OR_MAPPING_REVOKED");return;}
        var config=db.queryForMap("SELECT * FROM erp_connection WHERE id=?",c.id());
        ErpAdapter provider;
        try {provider=adapter(c);}catch(ErpReadFailure e){fail(id,e.code());return;}
        if(job.entityType().equals("METADATA")) {try {provider.verifyMetadata();checkpoint(job,null,2,2,0,0);audit(actor,request,"sync.metadata.complete",id,c.companyId(),null,"SYNC");}catch(ErpReadFailure e){fail(id,e);}return;}
        var requestData=request(c,job.scenario(),job.nextCursor(),2,job.attempts(),job.snapshotId());
        if(job.entityType().equals("GOODS_RECEIPT")) {
            Page<GoodsReceipt> rp;try {rp=provider.fetchGoodsReceipts(requestData);} catch(ErpReadFailure unavailable) {fail(id,unavailable);return;}
            if(!rp.snapshotId().equals(job.snapshotId())) {fail(id,"SNAPSHOT_CHANGED");return;}
            int applied=0,skipped=0,quarantined=readIssues(job,rp.issues(),c,actor,request);for(var v:rp.items()) {int result=receiptImporter.apply(c.id(),id,v);if(result==0) applied++;else if(result==1) skipped++;else quarantined++;}
            checkpoint(job,rp.nextCursor(),rp.items().size()+rp.issues().size(),applied,skipped,quarantined);if(rp.nextCursor()==null) audit(actor,request,"receipt.sync.complete",id,c.companyId(),null,"SYNC");return;
        }
        Page<PurchaseOrder> page;
        try {page=provider.fetchPurchaseOrders(requestData);} catch(ErpReadFailure unavailable) {fail(id,unavailable);return;}
        if(!page.snapshotId().equals(job.snapshotId())) {fail(id,"SNAPSHOT_CHANGED");return;}
        var plantMap=new HashMap<String,UUID>();db.query("SELECT external_key,plant_id FROM erp_plant_mapping WHERE connection_id=?",(RowCallbackHandler)r->plantMap.put(r.getString(1),r.getObject(2,UUID.class)),c.id());
        for(var material:provider.fetchMaterials(request(c,"INITIAL",null,100,0,job.snapshotId())).items()) db.update("INSERT INTO material(id,deployment_id,connection_id,external_key,name,unit) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1 ON CONFLICT(connection_id,external_key) DO NOTHING",UUID.randomUUID(),c.id(),material.source().externalKey(),material.name(),material.unit());
        if(provider.materialsFromOrders())for(var value:page.items())for(var item:value.items()) db.update("INSERT INTO material(id,deployment_id,connection_id,external_key,name,unit) SELECT ?,deployment_id,?,?,?,? FROM app_guard WHERE id=1 ON CONFLICT(connection_id,external_key) DO NOTHING",UUID.randomUUID(),c.id(),item.materialKey(),item.materialKey(),item.unit());
        var materialIds=new HashMap<String,UUID>();var units=new HashMap<String,String>();db.query("SELECT id,external_key,unit FROM material WHERE connection_id=?",(RowCallbackHandler)r->{materialIds.put(r.getString(2),r.getObject(1,UUID.class));units.put(r.getString(2),r.getString(3));},c.id());
        int applied=0,skipped=0,quarantined=readIssues(job,page.issues(),c,actor,request);
        for(var value:page.items()) {int result=apply(job,value,c,config,plantMap,materialIds,units,actor,request);if(result==0) applied++;else if(result==1) skipped++;else quarantined++;}
        String status=page.nextCursor()!=null?"RUNNING":job.quarantined()+quarantined>0?"QUARANTINED":"SUCCEEDED";
        db.update("UPDATE sync_job SET next_cursor=?,status=?,processed=processed+?,applied=applied+?,skipped=skipped+?,quarantined=quarantined+?,error_code=NULL,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",page.nextCursor(),status,page.items().size()+page.issues().size(),applied,skipped,quarantined,id);
        db.update("INSERT INTO sync_cursor(connection_id,snapshot_id,next_cursor) VALUES (?,?,?) ON CONFLICT(connection_id) DO UPDATE SET snapshot_id=excluded.snapshot_id,next_cursor=excluded.next_cursor,updated_at=CURRENT_TIMESTAMP",c.id(),page.snapshotId(),page.nextCursor());
        if(!status.equals("RUNNING")) audit(actor,request,"sync.complete",id,c.companyId(),null,"SYNC");
    }
    private int readIssues(Job job,List<ReadIssue> issues,Connection c,UUID actor,UUID request) {
        for(var issue:issues) {UUID id=UUID.randomUUID();db.update("INSERT INTO sync_issue(id,connection_id,job_id,external_key,source_revision,content_hash,error_code) VALUES (?,?,?,?,?,'adapter',?) ON CONFLICT DO NOTHING",id,c.id(),job.id(),issue.externalKey(),issue.sourceRevision(),issue.code());
            var orders=db.queryForList("SELECT id FROM purchase_order WHERE connection_id=? AND external_id=?",UUID.class,c.id(),issue.externalKey());for(UUID order:orders){db.update("UPDATE purchase_order SET consistency_hold=true,lock_version=lock_version+1 WHERE id=? AND NOT consistency_hold",order);collaboration.freeze(order);}audit(actor,request,"sync.quarantine",id,c.companyId(),null,"SYNC");}
        return issues.size();
    }
    private void checkpoint(Job job,String cursor,int processed,int applied,int skipped,int quarantined) {
        String status=cursor!=null?"RUNNING":job.quarantined()+quarantined>0?"QUARANTINED":"SUCCEEDED";
        db.update("UPDATE sync_job SET next_cursor=?,status=?,processed=processed+?,applied=applied+?,skipped=skipped+?,quarantined=quarantined+?,error_code=NULL,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",cursor,status,processed,applied,skipped,quarantined,job.id());
    }
    private void fail(UUID id,ErpReadFailure failure) {fail(id,failure.code(),failure.retryAfterSeconds());}
    private void fail(UUID id,String code) {fail(id,code,0);}
    private void fail(UUID id,String code,long retryAfter) {if(Set.of("SAP_TRANSPORT_FAILED","SAP_SERVER_UNAVAILABLE","SAP_RATE_LIMITED").contains(code)) {
            int attempts=db.queryForObject("SELECT attempts FROM sync_job WHERE id=?",Integer.class,id);
            if(attempts<5) {long delay=Math.max(retryAfter,Math.min(60,1L<<attempts)+java.util.concurrent.ThreadLocalRandom.current().nextInt(3));db.update("UPDATE sync_job SET status='PENDING',attempts=attempts+1,error_code=?,next_attempt_at=CURRENT_TIMESTAMP+(? * interval '1 second'),lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",code,delay,id);return;}
        }
        var r=db.queryForMap("SELECT j.actor_id,j.request_id,c.company_id FROM sync_job j JOIN erp_connection c ON c.id=j.connection_id WHERE j.id=?",id);audit((UUID)r.get("actor_id"),(UUID)r.get("request_id"),"sync.failed",id,(UUID)r.get("company_id"),null,"SYNC");db.update("UPDATE sync_job SET status='FAILED',error_code=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",code,id);db.update("INSERT INTO notification_delivery(id,deployment_id,origin_kind,origin_id,origin_version,event_type,job_id) SELECT gen_random_uuid(),deployment_id,'SYNC',id,lock_version,'sync.failed',id FROM sync_job WHERE id=? ON CONFLICT DO NOTHING",id);}
}
