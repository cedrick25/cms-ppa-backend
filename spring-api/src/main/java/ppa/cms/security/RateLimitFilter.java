package ppa.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ppa.cms.persistence.UserAccount;
import ppa.cms.support.JsonCodec;

public class RateLimitFilter extends OncePerRequestFilter {

    private final AttemptWindow attempts;

    public RateLimitFilter(AttemptWindow attempts) {
        this.attempts = attempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = rule.name() + ":" + actor(request);
        Duration window = Duration.ofMinutes(1);
        if (attempts.tooMany(key, rule.max(), window)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(attempts.retryAfterSeconds(key, window)));
            response.setContentType("application/json");
            response.getWriter().write(JsonCodec.write(java.util.Map.of("message", "Too Many Attempts.")));
            return;
        }
        attempts.hit(key, window);
        chain.doFilter(request, response);
    }

    private static String actor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return "user:" + user.getId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static Rule ruleFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.equals("/api/audit-trail/refresh") && method.equals("POST")) {
            return new Rule("audit-refresh", 6);
        }
        if (path.equals("/api/audit-trail") && method.equals("GET")) {
            return new Rule("audit", 60);
        }
        if (path.equals("/api/cms-audit-trail") && method.equals("GET")) {
            return new Rule("cms-audit", 60);
        }
        if (path.startsWith("/api/dashboard/") && method.equals("GET")) {
            return new Rule("dashboard", 60);
        }
        if (path.startsWith("/api/notifications")) {
            return new Rule("notifications", 120);
        }
        if (path.equals("/api/user") && method.equals("PATCH")) {
            return new Rule("account", 6);
        }
        if (path.startsWith("/api/users") && !method.equals("GET")) {
            return new Rule("users", 30);
        }
        if (path.equals("/api/systems") && method.equals("POST")) {
            return new Rule("systems", 30);
        }
        if (path.matches("/api/systems/\\d+(/remote-desks)?")) {
            return new Rule("systems", 30);
        }
        return null;
    }

    private record Rule(String name, int max) {
    }
}
