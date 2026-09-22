package com.conveyor.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authentication endpoints.
 *
 * These endpoints are publicly accessible — no JWT required.
 * (SecurityConfig explicitly permits /auth/** for unauthenticated access.)
 *
 * Base path: /auth
 *
 * Endpoints:
 *   POST /auth/register — create a new account
 *   POST /auth/login    — get a JWT for an existing account
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Creates a new user account.
     *
     * Request:  POST /auth/register
     *           Content-Type: application/json
     *           Body: { "email": "user@example.com", "password": "securepass123" }
     *
     * Response: 201 Created
     *           { "token": "eyJhbG...", "expiresInMilliseconds": 86400000, "userId": 1, "email": "user@example.com" }
     *
     * Errors:   400 — missing/invalid email or password (too short)
     *           400 — email already registered
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest registrationRequest) {
        AuthResponse newUserResponse = authService.registerNewUser(registrationRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(newUserResponse);
    }

    /**
     * Authenticates a user and returns a fresh JWT.
     *
     * Request:  POST /auth/login
     *           Content-Type: application/json
     *           Body: { "email": "user@example.com", "password": "securepass123" }
     *
     * Response: 200 OK
     *           { "token": "eyJhbG...", "expiresInMilliseconds": 86400000, "userId": 1, "email": "user@example.com" }
     *
     * Errors:   400 — invalid email or password
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        AuthResponse authenticatedUserResponse = authService.loginExistingUser(loginRequest);
        return ResponseEntity.ok(authenticatedUserResponse);
    }
}
