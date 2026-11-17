package us.minitv.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import us.minitv.common.mapper.CommonApiKeyEventMapper;

import java.io.IOException;

/**
 * Records one row per *write* request made with an API key.
 *
 * audit_logs already says which person acted; this fills in which credential
 * did it. GETs are skipped on purpose — they would dominate the table — and a
 * failure here never breaks the request.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiKeyEventFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyEventFilter.class);

    private final CommonApiKeyEventMapper mapper;

    public ApiKeyEventFilter(CommonApiKeyEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            Object keyId = request.getAttribute(AuthArgumentResolvers.ATTR_KEY_ID);
            Object staffId = request.getAttribute(AuthArgumentResolvers.ATTR_KEY_STAFF_ID);
            String method = request.getMethod();
            if (keyId instanceof Long key && staffId instanceof Long staff && ApiKeyService.EVENT_METHODS.contains(method)) {
                try {
                    String path = request.getRequestURI();
                    if (path.length() > 200) {
                        path = path.substring(0, 200);
                    }
                    mapper.insert(key, staff, method, path, response.getStatus(),
                            (int) (System.currentTimeMillis() - started));
                } catch (Exception ex) {
                    log.warn("api_key_event_write_failed", ex);
                }
            }
        }
    }
}
