package cn.zuoxu.climate.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/** API keys authenticate a service role, not an individual user or a JWT session. */
public final class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] readerKey;
    private final byte[] adminKey;
    private final ObjectMapper json;

    public ApiKeyFilter(String readerKey, String adminKey, ObjectMapper json) {
        if (readerKey.length() < 32 || adminKey.length() < 32 || readerKey.equals(adminKey)) {
            throw new IllegalArgumentException("Two different API keys of at least 32 characters are required; use scripts/local_auth.py");
        }
        this.readerKey = digest(readerKey);
        this.adminKey = digest(adminKey);
        this.json = json;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders("X-API-Key"));
        if (values.size() > 1) {
            writeError(response, json, 401, "API_KEY_INVALID", "Supply one X-API-Key header");
            return;
        }
        String supplied = values.isEmpty() ? null : values.get(0);
        if (supplied != null) {
            if (supplied.length() > 512) {
                writeError(response, json, 401, "API_KEY_INVALID", "Invalid API key");
                return;
            }
            byte[] hash = digest(supplied);
            boolean isAdmin = MessageDigest.isEqual(adminKey, hash);
            boolean isReader = MessageDigest.isEqual(readerKey, hash);
            if (!isAdmin && !isReader) {
                writeError(response, json, 401, "API_KEY_INVALID", "Invalid API key");
                return;
            }
            var authorities = AuthorityUtils.createAuthorityList(isAdmin ? new String[]{"ROLE_READER", "ROLE_ADMIN"} : new String[]{"ROLE_READER"});
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(isAdmin ? "admin-service" : "reader-service", null, authorities));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public static void writeError(HttpServletResponse response, ObjectMapper json, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), Map.of("error", error, "message", message, "requestId", String.valueOf(MDC.get("requestId"))));
    }
}
