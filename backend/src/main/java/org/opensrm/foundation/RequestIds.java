package org.opensrm.foundation;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds implements Filter {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    public static UUID current() { return CURRENT.get() == null ? UUID.randomUUID() : CURRENT.get(); }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        CURRENT.set(UUID.randomUUID());
        ((HttpServletResponse) response).setHeader("X-Request-ID",current().toString());
        try { chain.doFilter(request,response); } finally { CURRENT.remove(); }
    }
}
