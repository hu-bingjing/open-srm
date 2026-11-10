package org.opensrm.identity;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.opensrm.foundation.Problems;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean AuthenticationManager authenticationManager(IdentityService identities, PasswordEncoder encoder) {
        var provider=new DaoAuthenticationProvider(login->{
            var user=identities.credentials(login);
            if (user==null) throw new UsernameNotFoundException("Invalid credentials");
            return User.withUsername(user.login()).password(user.hash()).disabled(!user.status().equals("ACTIVE"))
                .authorities(identities.permissions(user.id()).toArray(String[]::new)).build();
        });
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityFilterChain security(HttpSecurity http, IdentityService identities, SecurityContextRepository contexts, CsrfTokenRepository csrf,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookies) throws Exception {
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                if (!List.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
                    String origin=request.getHeader("Origin"),site=request.getHeader("Sec-Fetch-Site");
                    boolean denied="cross-site".equals(site);
                    if(origin!=null)try {
                        var uri=java.net.URI.create(origin);
                        String host=request.getHeader("Host");
                        // TLS terminates at the proxy; trust the configured cookie mode,
                        // never a client-supplied Forwarded/X-Forwarded-Proto header.
                        String scheme=secureCookies ? "https" : "http";
                        denied |= uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !origin.equals(scheme+"://"+host);
                    }catch(Exception invalid){denied=true;}
                    if(denied){Problems.write(response,403,"CROSS_ORIGIN_WRITE_DENIED");return;}
                }
                chain.doFilter(request,response);
            }
        },CsrfFilter.class);
        http.csrf(c->c.csrfTokenRepository(csrf))
            .securityContext(c->c.securityContextRepository(contexts).requireExplicitSave(true))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .authorizeHttpRequests(a->a
                .requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/auth/bootstrap","/api/v1/auth/bootstrap/status","/actuator/health/**","/actuator/health").permitAll()
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((q,s,x)->Problems.write(s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((q,s,x)->Problems.write(s,403,"FORBIDDEN_OR_CSRF")))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                    var context=SecurityContextHolder.getContext();
                    var auth=context.getAuthentication();
                    if (auth!=null && auth.getPrincipal() instanceof SessionUser current) {
                        var active=identities.activeSession(current);
                        if (active.isEmpty()) {
                            context.setAuthentication(null);
                            var session=request.getSession(false);
                            if (session!=null) session.invalidate();
                        } else {
                            context.setAuthentication(new UsernamePasswordAuthenticationToken(current,null,
                                identities.permissions(current.id()).stream().map(SimpleGrantedAuthority::new).toList()));
                        }
                    }
                    chain.doFilter(request,response);
                }
            },SecurityContextHolderFilter.class);
        return http.build();
    }
}
