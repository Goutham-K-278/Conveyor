package com.conveyor.config;

import com.conveyor.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures Spring Security for the Conveyor API.
 *
 * ─── SECURITY MODEL ──────────────────────────────────────────────────────────
 * Conveyor uses stateless JWT authentication:
 *   - No server-side sessions (STATELESS session policy)
 *   - No CSRF tokens (not needed for stateless APIs — CSRF exploits sessions)
 *   - No form login or HTTP Basic (only Bearer tokens)
 *
 * ─── WHAT IS PERMITTED ───────────────────────────────────────────────────────
 *   Public (no token needed):
 *     POST /auth/register — anyone can register
 *     POST /auth/login    — anyone can log in
 *
 *   Protected (valid JWT required):
 *     ALL other endpoints — must have "Authorization: Bearer <token>"
 *
 * ─── FILTER ORDER ────────────────────────────────────────────────────────────
 * We insert our JwtAuthenticationFilter BEFORE Spring's default
 * UsernamePasswordAuthenticationFilter. This means JWT validation runs first,
 * and if successful, the downstream authorization check sees an authenticated user.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain configureSecurityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
            // ── Disable CSRF — not needed for stateless REST APIs ─────────────
            .csrf(AbstractHttpConfigurer::disable)

            // ── Stateless sessions — no server-side session storage ───────────
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // ── URL Authorization Rules ───────────────────────────────────────
            .authorizeHttpRequests(authorizationRules ->
                authorizationRules
                    // Auth endpoints are public — anyone can register or log in
                    .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                    // Static UI files are public
                    .requestMatchers(HttpMethod.GET, "/", "/index.html", "/css/**", "/js/**").permitAll()
                    // All other requests require a valid JWT
                    .anyRequest().authenticated()
            )

            // ── Register the JWT filter before the default auth filter ────────
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return httpSecurity.build();
    }
}
