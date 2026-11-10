package org.opensrm.access;
import java.util.UUID;
import jakarta.validation.Valid;
import org.opensrm.identity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AccessController {
    private final AccessService service;
    public AccessController(AccessService service) { this.service=service; }
    @GetMapping("/auth/access") Object own(@AuthenticationPrincipal SessionUser actor) { return service.own(actor); }
    @GetMapping("/users/{id}/grants") Object grants(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { return service.list(actor,id); }
    @PostMapping("/users/{id}/grants") ResponseEntity<String> add(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody AccessService.GrantInput input) { return IdentityController.response(service.add(actor,id,key,etag,input),201); }
    @DeleteMapping("/users/{id}/grants/{grant}") ResponseEntity<String> revoke(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@PathVariable UUID grant,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) { return IdentityController.response(service.revoke(actor,id,grant,key,etag),200); }
    @GetMapping("/users/{id}/supplier-binding") Object binding(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { return service.getBinding(actor,id); }
    @PutMapping("/users/{id}/supplier-binding") ResponseEntity<String> bind(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody AccessService.Binding input) { return IdentityController.response(service.bind(actor,id,key,etag,input),200); }
}
