package org.opensrm.identity;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class IdentityController {
    private final IdentityService identities;
    public IdentityController(IdentityService identities) { this.identities=identities; }
    public static ResponseEntity<String> response(IdentityService.StoredResult result,int status) {
        return ResponseEntity.status(status).header("Content-Type","application/json").eTag(result.etag()).body(result.json());
    }
    @GetMapping("/users") @PreAuthorize("hasAuthority('user.manage')")
    Object users(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return identities.users(page,size); }
    @GetMapping("/users/{id}") @PreAuthorize("hasAuthority('user.manage')")
    ResponseEntity<?> user(@PathVariable UUID id) { var value=identities.user(id);return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value); }
    @PostMapping("/users") @PreAuthorize("hasAuthority('user.manage')")
    ResponseEntity<String> create(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody IdentityService.NewUser value) {
        return response(identities.create(actor,key,value),201);
    }
    @PatchMapping("/users/{id}") @PreAuthorize("hasAuthority('user.manage')")
    ResponseEntity<String> update(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,@Valid @RequestBody IdentityService.UpdateUser value) { return response(identities.update(actor,id,key,etag,value),200); }
    @PutMapping("/users/{id}/roles") @PreAuthorize("hasAuthority('user.manage')")
    ResponseEntity<String> roles(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,@Valid @RequestBody IdentityService.UpdateRoles value) { return response(identities.updateRoles(actor,id,key,etag,value),200); }
}
