package org.opensrm.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupGuard implements ApplicationRunner {
    private final IdentityService identities;
    private final String environment,bootstrap,adapter,password,idempotencyKey;
    private final boolean secure,write;
    public StartupGuard(IdentityService identities,@Value("${opensrm.environment}") String environment,
        @Value("${opensrm.bootstrap-token}") String bootstrap,@Value("${opensrm.erp-adapter}") String adapter,
        @Value("${opensrm.erp-write-enabled}") boolean write,@Value("${server.servlet.session.cookie.secure}") boolean secure,
        @Value("${spring.datasource.password}") String password,@Value("${opensrm.idempotency-key}") String idempotencyKey) {
        this.identities=identities;this.environment=environment;this.bootstrap=bootstrap;this.adapter=adapter;this.write=write;this.secure=secure;this.password=password;this.idempotencyKey=idempotencyKey;
    }
    @Override public void run(ApplicationArguments args) {
        if (!java.util.Set.of("mock","sap-readonly").contains(adapter) || write) throw new IllegalStateException("Only registered read-only ERP adapters are permitted; ERP writes must remain disabled");
        if (!secure && !environment.equals("local") && !environment.equals("test")) throw new IllegalStateException("Secure session cookies required outside local/test");
        if (password.isBlank() || password.equals("CHANGE_ME")) throw new IllegalStateException("Database credential required");
        if (idempotencyKey.length()<32) throw new IllegalStateException("Random idempotency HMAC key required");
        if (!identities.initialized() && (bootstrap.length()<32 || bootstrap.equals("CHANGE_ME"))) throw new IllegalStateException("Initial setup requires a random bootstrap token of at least 32 characters");
    }
}
