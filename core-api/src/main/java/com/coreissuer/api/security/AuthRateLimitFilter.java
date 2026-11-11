package com.coreissuer.api.security;

import com.coreissuer.common.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final long REQUEST_LIMIT = 10;
    private final StringRedisTemplate redis;

    public AuthRateLimitFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String addressHash = CryptoUtils.simpleHash(request.getRemoteAddr());
            String key = "rate:auth:" + addressHash;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofMinutes(1));
            if (count == null || count > REQUEST_LIMIT) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
                return;
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limiter_unavailable\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
