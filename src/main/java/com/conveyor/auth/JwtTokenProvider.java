package com.conveyor.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Creates and validates JWT tokens for Conveyor's authentication system.
 *
 * ─── HOW JWT WORKS HERE ───────────────────────────────────────────────────────
 * When a user logs in:
 *   1. We create a JWT containing: userId + email + issued_at + expires_at
 *   2. We sign it with our secret key (HS256 — HMAC with SHA-256)
 *   3. We return it to the client
 *
 * On every subsequent request:
 *   1. The client sends the token in the Authorization header
 *   2. JwtAuthenticationFilter intercepts the request and calls validateToken()
 *   3. If valid → we extract the userId and put it in the SecurityContext
 *   4. Controllers receive the userId via @AuthenticationPrincipal
 *
 * ─── WHY STATELESS JWT? ──────────────────────────────────────────────────────
 * We don't store tokens in the database. The signature is the proof of validity.
 * This means:
 *   - Workers and API servers can all validate tokens independently
 *   - No "session store" is needed — scales horizontally without Redis sessions
 *   - Downside: tokens can't be revoked before expiry (acceptable for this system)
 */
@Slf4j
@Component
public class JwtTokenProvider {

    // The claim name we use to store the user's database ID inside the token
    private static final String USER_ID_CLAIM = "userId";

    @Value("${jwt.secret}")
    private String jwtSecretString;

    @Value("${jwt.expiration-milliseconds}")
    private long tokenExpirationMilliseconds;

    /**
     * Generates a signed JWT token for an authenticated user.
     *
     * The token payload (claims) contains:
     *   - subject: the user's email (standard JWT "sub" field)
     *   - userId:  the user's database ID (custom claim)
     *   - iat:     issued-at timestamp
     *   - exp:     expiry timestamp (iat + 24 hours)
     *
     * @param userId The authenticated user's database ID
     * @param email  The authenticated user's email address
     * @return A signed JWT string ready to return to the client
     */
    public String generateToken(Long userId, String email) {
        Date issuedAt = new Date();
        Date expiresAt = new Date(issuedAt.getTime() + tokenExpirationMilliseconds);

        return Jwts.builder()
                .subject(email)
                .claim(USER_ID_CLAIM, userId)
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .signWith(buildSigningKey())
                .compact();
    }

    /**
     * Validates a token's signature and expiry.
     *
     * Returns false (instead of throwing) for any validation failure —
     * this simplifies the filter code which just checks true/false.
     *
     * @param token The raw JWT string from the Authorization header
     * @return true if the token is valid and not expired, false otherwise
     */
    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token); // Throws if invalid or expired
            return true;
        } catch (JwtException invalidToken) {
            log.warn("JWT validation failed: {}", invalidToken.getMessage());
            return false;
        } catch (IllegalArgumentException emptyToken) {
            log.warn("JWT token was null or empty");
            return false;
        }
    }

    /**
     * Extracts the user's database ID from a valid token.
     * Only call this after confirming the token is valid with isTokenValid().
     *
     * @param token The validated JWT string
     * @return The user's database ID stored in the token's userId claim
     */
    public Long extractUserId(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get(USER_ID_CLAIM, Long.class);
    }

    /**
     * Extracts the user's email (the token "subject") from a valid token.
     *
     * @param token The validated JWT string
     * @return The user's email stored as the token subject
     */
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(buildSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Converts the plain-text secret string into a cryptographic key.
     * Keys.hmacShaKeyFor() ensures the key is the correct length for HS256.
     */
    private SecretKey buildSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecretString.getBytes(StandardCharsets.UTF_8));
    }
}
