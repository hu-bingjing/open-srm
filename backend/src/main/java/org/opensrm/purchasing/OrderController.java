package org.opensrm.purchasing;
import java.util.UUID;
import jakarta.validation.constraints.*;
import org.opensrm.identity.SessionUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/purchase-orders") @Validated
public class OrderController {
 private final OrderService service;
 public OrderController(OrderService service) {this.service=service;}
 @GetMapping Object list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=160) String query,@RequestParam(required=false) UUID companyId,@RequestParam(required=false) UUID plantId,@RequestParam(required=false) UUID purchasingOrgId,@RequestParam(required=false) UUID supplierId) {return service.list(actor,page,size,query,companyId,plantId,purchasingOrgId,supplierId);}
 @GetMapping("/{id}") ResponseEntity<?> get(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var o=service.get(actor,id);return ResponseEntity.ok().eTag(Long.toString(o.version())).body(o);}
 @GetMapping("/{id}/revisions") Object revisions(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.revisions(actor,id,page,size);}
}
