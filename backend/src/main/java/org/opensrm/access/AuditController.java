package org.opensrm.access;
import java.util.UUID;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @Validated
public class AuditController {
    private final AccessPolicy policy;private final NamedParameterJdbcTemplate sql;
    public AuditController(AccessPolicy policy,JdbcTemplate db) { this.policy=policy;this.sql=new NamedParameterJdbcTemplate(db); }
    @GetMapping("/audit-events") Object list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        var ctx=policy.context(actor,"audit.read");var args=policy.parameters(ctx).addValue("limit",size).addValue("offset",(long)page*size);
        String where="e.deployment_id="+AccessPolicy.REALM+" AND "+policy.currentActor()+" AND (e.category='IDENTITY' OR EXISTS(SELECT 1 FROM audit_event_company ec WHERE ec.event_id=e.id AND "+policy.grant("ec.company_id","NULL","NULL",AccessPolicy.Permission.AUDIT)+"))";
        var rows=sql.query("SELECT e.* FROM audit_event e WHERE "+where+" ORDER BY e.created_at DESC,e.id LIMIT :limit OFFSET :offset",args,(r,n)->new IdentityService.AuditView(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("operation"),r.getObject("object_id",UUID.class),r.getObject("request_id",UUID.class),r.getTimestamp("created_at").toInstant().toString()));
        return new IdentityService.Page<>(rows,sql.queryForObject("SELECT count(*) FROM audit_event e WHERE "+where,args,Long.class),page,size);
    }
}
