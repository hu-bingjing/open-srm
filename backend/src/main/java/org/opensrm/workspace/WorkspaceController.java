package org.opensrm.workspace;
import java.util.UUID;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.opensrm.notification.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") @Validated
public class WorkspaceController {
 private final WorkspaceService workspace;private final NotificationService notices;
 public WorkspaceController(WorkspaceService workspace,NotificationService notices) {this.workspace=workspace;this.notices=notices;}
 @GetMapping("/dashboard") Object dashboard(@AuthenticationPrincipal SessionUser actor) {return workspace.dashboard(actor);}
 @GetMapping("/statistics") Object statistics(@AuthenticationPrincipal SessionUser actor,@RequestParam(required=false) UUID companyId,@RequestParam(required=false) UUID plantId,@RequestParam(required=false) UUID purchasingOrgId,@RequestParam(required=false) UUID supplierId) {return workspace.statistics(actor,new WorkspaceService.Filter(companyId,plantId,purchasingOrgId,supplierId));}
 @GetMapping("/notifications") Object list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="false") boolean unread) {return notices.list(actor,page,size,unread);}
 @GetMapping("/notifications/unread-count") Object unread(@AuthenticationPrincipal SessionUser actor) {return java.util.Map.of("count",notices.unread(actor));}
 @GetMapping("/notifications/{id}") ResponseEntity<?> notice(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var n=notices.get(actor,id);return ResponseEntity.ok().eTag(Long.toString(n.version())).body(n);}
 @PostMapping("/notifications/{id}/read") ResponseEntity<String> read(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(notices.read(actor,id,key,etag),200);}
 @GetMapping("/notification-deliveries") Object deliveries(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return notices.deliveries(actor,page,size);}
 @PostMapping("/notification-deliveries/{id}/retry") ResponseEntity<String> retry(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(notices.retry(actor,id,key,etag),202);}
}
