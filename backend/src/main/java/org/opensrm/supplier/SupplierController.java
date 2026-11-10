package org.opensrm.supplier;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/suppliers") @Validated
public class SupplierController {
    private final SupplierService service;
    public SupplierController(SupplierService service) { this.service=service; }
    @GetMapping Object list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=160) String query,@RequestParam(required=false) UUID companyId) { return service.list(actor,page,size,query,companyId); }
    @GetMapping("/{id}") ResponseEntity<?> get(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { var v=service.get(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v); }
    @PostMapping ResponseEntity<String> create(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SupplierService.Create input) { return IdentityController.response(service.create(actor,key,input),201); }
    @PatchMapping("/{id}") ResponseEntity<String> update(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody SupplierService.Update input) { return IdentityController.response(service.update(actor,id,key,etag,input),200); }
    @PostMapping("/{id}/companies") ResponseEntity<String> associate(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody SupplierService.Link input) { return IdentityController.response(service.associate(actor,id,key,etag,input),200); }
}
