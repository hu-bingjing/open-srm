package org.opensrm.identity;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.opensrm.foundation.ApiProblem;
import org.opensrm.foundation.RequestIds;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Login(@NotBlank @Size(max=80) String login,@NotBlank @Size(max=256) String password) { }
    public record Bootstrap(@NotBlank @Size(max=256) String bootstrapToken,
        @Pattern(regexp="[a-z0-9][a-z0-9._-]{2,79}") @NotBlank String login,
        @NotBlank @Size(max=120) String displayName,@NotBlank @Size(min=12,max=72) String password) { }
    private final IdentityService identities;
    private final AuthenticationManager auth;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final CsrfTokenRepository csrf;
    private final String bootstrapToken;
    public AuthController(IdentityService identities,AuthenticationManager auth,SecurityContextRepository contexts,
                          SessionAuthenticationStrategy strategy,CsrfTokenRepository csrf,@Value("${opensrm.bootstrap-token}") String bootstrapToken) {
        this.identities=identities;this.auth=auth;this.contexts=contexts;this.strategy=strategy;this.csrf=csrf;this.bootstrapToken=bootstrapToken;
    }
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken value) { return Map.of("csrfToken",value.getToken()); }
    @GetMapping("/bootstrap/status") Map<String,Boolean> bootstrapStatus() { return Map.of("setupRequired",!identities.initialized()); }
    @PostMapping("/bootstrap") ResponseEntity<?> bootstrap(@Valid @RequestBody Bootstrap value) {
        return ResponseEntity.status(201).body(identities.bootstrap(new IdentityService.NewUser(value.login(),value.displayName(),value.password(),List.of("ADMIN")),value.bootstrapToken(),bootstrapToken));
    }
    @PostMapping("/login") ResponseEntity<?> login(@Valid @RequestBody Login value,HttpServletRequest request,HttpServletResponse response) {
        if (identities.loginBlocked(value.login())) throw new ApiProblem(429,"LOGIN_THROTTLED");
        Authentication authenticated;
        try {
            if (value.password().getBytes(StandardCharsets.UTF_8).length>72) throw new BadCredentialsException("Invalid credentials");
            authenticated=auth.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(value.login(),value.password()));
        }
        catch (AuthenticationException failure) {
            identities.loginFailed(value.login());
            LoggerFactory.getLogger(AuthController.class).warn("event=authentication_denied code=INVALID_CREDENTIALS requestId={}",RequestIds.current());
            throw new ApiProblem(401,"INVALID_CREDENTIALS");
        }
        var user=identities.credentials(authenticated.getName());
        var principal=new SessionUser(user.id(),user.login(),user.sessionVersion());
        var sessionAuth=new UsernamePasswordAuthenticationToken(principal,null,authenticated.getAuthorities());
        identities.loginAudit(user.id());
        strategy.onAuthentication(sessionAuth,request,response);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(sessionAuth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        identities.loginSucceeded(user.login());
        return ResponseEntity.ok().eTag(Long.toString(identities.user(user.id()).version())).body(identities.user(user.id()));
    }
    @GetMapping("/me") ResponseEntity<?> me(@AuthenticationPrincipal SessionUser actor) {
        var user=identities.user(actor.id());
        return ResponseEntity.ok().eTag(Long.toString(user.version())).body(user);
    }
    @PatchMapping("/profile") ResponseEntity<String> profile(@AuthenticationPrincipal SessionUser actor,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String etag,@Valid @RequestBody IdentityService.UpdateProfile value) {
        return IdentityController.response(identities.profile(actor,key,etag,value),200);
    }
    @PostMapping("/logout") ResponseEntity<Void> logout(@AuthenticationPrincipal SessionUser actor,HttpServletRequest request,HttpServletResponse response) {
        identities.logoutAudit(actor.id());
        csrf.saveToken(null,request,response);
        var session=request.getSession(false); if(session!=null) session.invalidate();
        SecurityContextHolder.clearContext();
        var cookie=new Cookie("OPEN_SRM_SESSION",""); cookie.setPath("/");cookie.setMaxAge(0);cookie.setHttpOnly(true);cookie.setSecure(request.isSecure());cookie.setAttribute("SameSite","Lax");response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
}
