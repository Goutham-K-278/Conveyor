package com.conveyor.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Intercepts every HTTP request, extracts the JWT from the Authorization header,
 * validates it, and populates the Spring SecurityContext with the user's identity.
 *
 * ─── HOW THIS FITS INTO THE SECURITY CHAIN ───────────────────────────────────
 * Spring Security processes requests through a chain of filters.
 * This filter runs before the authorization check:
 *
 *   Request → [this filter] → Authorization check → Controller
 *
 * If this filter successfully authenticates the request, Spring allows it through.
 * If the token is missing or invalid, SecurityContext stays empty, and Spring
 * returns 401 Unauthorized before the request ever reaches a controller.
 *
 * ─── OncePerRequestFilter ────────────────────────────────────────────────────
 * We extend OncePerRequestFilter (not GenericFilterBean) to guarantee this
 * filter runs exactly once per request, even in complex forwarding scenarios.
 *
 * ─── AUTHENTICATION PRINCIPAL ────────────────────────────────────────────────
 * We store the userId (Long) as the "principal" in the authentication object.
 * Controllers then receive it via @AuthenticationPrincipal Long userId.
 * This eliminates the need for controllers to parse the token themselves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BEARER_TOKEN_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String rawToken = extractBearerTokenFromHeader(request);
        if (rawToken == null) {
            rawToken = request.getParameter("token");
        }

        if (rawToken != null && jwtTokenProvider.isTokenValid(rawToken)) {
            Long authenticatedUserId = jwtTokenProvider.extractUserId(rawToken);

            // Create a Spring Security authentication object with the userId as principal
            // We pass empty credentials (null) and empty authorities (no roles needed here)
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    authenticatedUserId,   // principal — accessible via @AuthenticationPrincipal
                    null,                  // credentials — not needed after validation
                    Collections.emptyList() // authorities — no role-based auth in this system
                );

            // Store in SecurityContext — Spring's authorization check reads from here
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated user ID {} for request: {} {}", authenticatedUserId, request.getMethod(), request.getRequestURI());
        }

        // Always continue the filter chain — security decisions happen downstream
        filterChain.doFilter(request, response);
    }

    /**
     * Reads the Authorization header and strips the "Bearer " prefix.
     * Returns null if the header is missing or doesn't start with "Bearer ".
     */
    private String extractBearerTokenFromHeader(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER_NAME);

        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith(BEARER_TOKEN_PREFIX)) {
            return authorizationHeader.substring(BEARER_TOKEN_PREFIX.length());
        }

        return null;
    }
}
