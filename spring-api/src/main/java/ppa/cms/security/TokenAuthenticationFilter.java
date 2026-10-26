package ppa.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ppa.cms.persistence.ApiToken;
import ppa.cms.persistence.ApiTokenRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.support.JsonCodec;

public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final ApiTokenRepository tokens;
    private final UserAccountRepository users;

    public TokenAuthenticationFilter(ApiTokenRepository tokens, UserAccountRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        String plain = header.substring(7).trim();
        if (plain.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ApiToken token = tokens.findByToken(sha256(plain)).orElse(null);
        UserAccount user = token == null ? null : users.findById(token.getUserId()).orElse(null);
        if (token == null || user == null) {
            write(response, 401, "Unauthenticated.");
            return;
        }
        if (user.disabled()) {
            tokens.delete(token);
            write(response, 401, Access.DISABLED);
            return;
        }
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(Instant.now().minusSeconds(60))) {
            token.setLastUsedAt(Instant.now());
            tokens.save(token);
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, token, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte piece : digest) {
                hex.append(String.format("%02x", piece));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(JsonCodec.write(java.util.Map.of("message", message)));
    }
}
