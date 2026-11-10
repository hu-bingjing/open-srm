package org.opensrm.collaboration;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") @Validated
public class CollaborationController {
 private final CollaborationService service;
 public CollaborationController(CollaborationService service) {this.service=service;}
 @GetMapping({"/confirmations","/delivery-changes","/delivery-plans"}) Object list(@AuthenticationPrincipal SessionUser actor,jakarta.servlet.http.HttpServletRequest request,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(required=false) UUID orderId,@RequestParam(required=false) UUID companyId,@RequestParam(required=false) UUID plantId,@RequestParam(defaultValue="") @Size(max=20) String status) {
  String kind=switch(request.getRequestURI()) {case "/api/v1/confirmations"->"confirmation";case "/api/v1/delivery-changes"->"delivery_change";case "/api/v1/delivery-plans"->"delivery_plan";default->throw new IllegalArgumentException();};return service.list(actor,kind,page,size,orderId,companyId,plantId,status);
 }
 @GetMapping("/confirmations/{id}") ResponseEntity<?> confirmation(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var v=service.confirmation(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v);}
 @GetMapping("/delivery-changes/{id}") ResponseEntity<?> change(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var v=service.change(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v);}
 @GetMapping("/delivery-plans/{id}") ResponseEntity<?> plan(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {var v=service.plan(actor,id);return ResponseEntity.ok().eTag(Long.toString(v.version())).body(v);}
 @GetMapping("/delivery-changes/{id}/approval-events") Object approvals(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {return service.approvals(actor,id);}
 @PostMapping("/purchase-orders/{id}/confirmations") ResponseEntity<String> submit(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody CollaborationService.ConfirmInput input) {return IdentityController.response(service.submit(actor,id,key,etag,input),201);}
 @PostMapping("/purchase-orders/{id}/delivery-changes") ResponseEntity<String> request(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody CollaborationService.ChangeInput input) {return IdentityController.response(service.requestChange(actor,id,key,etag,input),201);}
 @PostMapping("/delivery-changes/{id}/decision") ResponseEntity<String> decide(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody CollaborationService.Decision input) {return IdentityController.response(service.decide(actor,id,key,etag,input),200);}
 @PostMapping("/delivery-changes/{id}/withdraw") ResponseEntity<String> withdraw(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(service.withdraw(actor,id,key,etag),200);}
}
