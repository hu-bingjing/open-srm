package org.opensrm.shipping;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.opensrm.identity.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") @Validated
public class ShippingController {
 private final ShippingService service;
 public ShippingController(ShippingService service) {this.service=service;}
 @GetMapping("/asns") Object list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.list(actor,page,size);}
 @GetMapping("/asns/{id}") Object get(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {return service.get(actor,id);}
 @GetMapping("/purchase-orders/{id}/shipping-balances") Object balances(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {return service.balances(actor,id);}
 @GetMapping("/goods-receipts") Object receipts(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return service.receipts(actor,page,size);}
 @GetMapping("/goods-receipts/{id}") Object receipt(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id) {return service.receipt(actor,id);}
 @PostMapping("/asns") Object create(@AuthenticationPrincipal SessionUser actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ShippingService.Input input) {return IdentityController.response(service.create(actor,key,input),201);}
 @PutMapping("/asns/{id}") Object update(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody ShippingService.Input input) {return IdentityController.response(service.update(actor,id,key,etag,input),200);}
 @PostMapping("/asns/{id}/submit") Object submit(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(service.transition(actor,id,key,etag,false),200);}
 @PostMapping("/asns/{id}/cancel") Object cancel(@AuthenticationPrincipal SessionUser actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag) {return IdentityController.response(service.transition(actor,id,key,etag,true),200);}
}
