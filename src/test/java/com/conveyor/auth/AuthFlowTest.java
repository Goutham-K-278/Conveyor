package com.conveyor.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JwtTokenProvider and AuthService token flow.
 *
 * Tests JWT generation, validation, and claim extraction in isolation —
 * no HTTP calls, no database, no Spring context.
 */
@DisplayName("Auth — JWT Token Tests")
class AuthFlowTest {

    private JwtTokenProvider jwtTokenProvider;

    // Must be at least 256 bits (32 chars) for HS256
    private static final String TEST_SECRET = "test-secret-that-is-long-enough-for-hs256-hashing";
    private static final long TOKEN_EXPIRY_24_HOURS = 86_400_000L;

    @BeforeEach
    void setupJwtProvider() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretString", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "tokenExpirationMilliseconds", TOKEN_EXPIRY_24_HOURS);
    }

    @Test
    @DisplayName("Generated token should be valid immediately after creation")
    void shouldGenerateValidToken() {
        String token = jwtTokenProvider.generateToken(1L, "user@example.com");

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("User ID extracted from token should match the one used to create it")
    void shouldExtractCorrectUserIdFromToken() {
        Long expectedUserId = 42L;
        String token = jwtTokenProvider.generateToken(expectedUserId, "user@example.com");

        Long extractedUserId = jwtTokenProvider.extractUserId(token);

        assertThat(extractedUserId).isEqualTo(expectedUserId);
    }

    @Test
    @DisplayName("Email extracted from token should match the one used to create it")
    void shouldExtractCorrectEmailFromToken() {
        String expectedEmail = "user@example.com";
        String token = jwtTokenProvider.generateToken(1L, expectedEmail);

        String extractedEmail = jwtTokenProvider.extractEmail(token);

        assertThat(extractedEmail).isEqualTo(expectedEmail);
    }

    @Test
    @DisplayName("Tampered token should fail validation")
    void shouldRejectTamperedToken() {
        String validToken = jwtTokenProvider.generateToken(1L, "user@example.com");
        // Append garbage to tamper with the signature
        String tamperedToken = validToken + "tampered";

        assertThat(jwtTokenProvider.isTokenValid(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("Completely invalid token string should fail validation gracefully")
    void shouldRejectCompletelyInvalidTokenString() {
        assertThat(jwtTokenProvider.isTokenValid("not-a-jwt-at-all")).isFalse();
    }

    @Test
    @DisplayName("Null token should fail validation gracefully without throwing")
    void shouldHandleNullTokenWithoutThrowingException() {
        assertThat(jwtTokenProvider.isTokenValid(null)).isFalse();
    }

    @Test
    @DisplayName("Expired token should fail validation")
    void shouldRejectExpiredToken() {
        // Create a provider with a -1ms expiry (immediately expired)
        JwtTokenProvider expiredTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(expiredTokenProvider, "jwtSecretString", TEST_SECRET);
        ReflectionTestUtils.setField(expiredTokenProvider, "tokenExpirationMilliseconds", -1L);

        String alreadyExpiredToken = expiredTokenProvider.generateToken(1L, "user@example.com");

        assertThat(jwtTokenProvider.isTokenValid(alreadyExpiredToken)).isFalse();
    }
}
