package org.opensrm.organization;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @Validated
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service=service; }
    @GetMapping("/companies") Object companies(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=160) String query,@RequestParam(required=false) UUID plantId,@RequestParam(required=false) UUID purchasingOrgId) { return service.companies(actor,page,size,query,plantId,purchasingOrgId); }
    @GetMapping("/companies/{id}") ResponseEntity<?> company(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { var v=service.company(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v); }
    @PostMapping("/companies") ResponseEntity<String> createCompany(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrganizationService.CompanyInput input) { return IdentityController.response(service.createCompany(actor,key,input),201); }
    @PatchMapping("/companies/{id}") ResponseEntity<String> updateCompany(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody OrganizationService.CompanyInput input) { return IdentityController.response(service.updateCompany(actor,id,key,etag,input),200); }
    @GetMapping("/plants") Object plants(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=160) String query,@RequestParam(required=false) UUID companyId,@RequestParam(required=false) UUID purchasingOrgId) { return service.plants(actor,page,size,query,companyId,purchasingOrgId); }
    @GetMapping("/plants/{id}") ResponseEntity<?> plant(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { var v=service.plant(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v); }
    @PostMapping("/plants") ResponseEntity<String> createPlant(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrganizationService.PlantInput input) { return IdentityController.response(service.createPlant(actor,key,input),201); }
    @PatchMapping("/plants/{id}") ResponseEntity<String> updatePlant(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody OrganizationService.PlantInput input) { return IdentityController.response(service.updatePlant(actor,id,key,etag,input),200); }
    @GetMapping("/purchasing-organizations") Object purchasing(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=160) String query,@RequestParam(required=false) UUID companyId,@RequestParam(required=false) UUID plantId) { return service.purchasingOrganizations(actor,page,size,query,companyId,plantId); }
    @GetMapping("/purchasing-organizations/{id}") ResponseEntity<?> purchasing(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) { var v=service.purchasingOrganization(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v); }
    @PostMapping("/purchasing-organizations") ResponseEntity<String> createPurchasing(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrganizationService.PurchasingInput input) { return IdentityController.response(service.createPurchasing(actor,key,input),201); }
    @PatchMapping("/purchasing-organizations/{id}") ResponseEntity<String> updatePurchasing(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody OrganizationService.PurchasingInput input) { return IdentityController.response(service.updatePurchasing(actor,id,key,etag,input),200); }
}
