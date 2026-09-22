package com.conveyor.auth;

import lombok.Builder;
import lombok.Getter;

/**
 * Response body returned after successful registration or login.
 * The client stores this token and includes it in all subsequent requests
 * as: Authorization: Bearer <token>
 */
@Getter
@Builder
public class AuthResponse {

    /** The signed JWT — valid for the duration shown in expiresInMilliseconds */
    private final String token;

    /** How long the token is valid (in milliseconds) — same as jwt.expiration-milliseconds in config */
    private final long expiresInMilliseconds;

    /** The authenticated user's database ID — useful for client-side state management */
    private final Long userId;

    /** The authenticated user's email */
    private final String email;
}
