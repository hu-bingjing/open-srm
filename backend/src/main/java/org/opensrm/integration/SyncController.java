package org.opensrm.integration;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") @Validated
public class SyncController {
 private final SyncService service;
 public SyncController(SyncService service) {this.service=service;}
 @GetMapping("/erp-adapters") Object adapters(@AuthenticationPrincipal SessionUser actor) {return service.capabilities(actor);}
 @GetMapping("/erp-connections") Object connections(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.connections(actor,page,size);}
 @GetMapping("/erp-connections/{id}") Object connection(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {return service.connection(actor,id);}
 @PostMapping("/erp-connections") ResponseEntity<String> create(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SyncService.ConnectionInput input) {return IdentityController.response(service.create(actor,key,input),201);}
 @GetMapping("/sync-jobs") Object jobs(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.jobs(actor,page,size);}
 @GetMapping("/sync-jobs/{id}") ResponseEntity<?> job(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var j=service.job(actor,id);return ResponseEntity.ok().eTag(Long.toString(j.version())).body(j);}
 @PostMapping("/sync-jobs") ResponseEntity<String> start(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SyncService.Start input) {return IdentityController.response(service.start(actor,key,input),202);}
 @PostMapping("/sync-jobs/{id}/retry") ResponseEntity<String> retry(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(service.retry(actor,id,key,etag),202);}
 @GetMapping("/sync-jobs/{id}/issues") Object issues(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.issues(actor,id,page,size);}
}
