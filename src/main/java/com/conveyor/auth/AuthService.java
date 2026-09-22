package com.conveyor.auth;

import com.conveyor.user.User;
import com.conveyor.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles user registration and login.
 *
 * ─── PASSWORD SECURITY ────────────────────────────────────────────────────────
 * Passwords are hashed with BCrypt before storage.
 * BCrypt is a one-way hash — even if the database is compromised,
 * plaintext passwords cannot be recovered. It also includes a random "salt"
 * per password so identical passwords produce different hashes.
 *
 * We use strength factor 12 (BCrypt default is 10):
 *   - Each hash takes ~300ms to compute on a modern CPU
 *   - This is imperceptible to a real user (login once every few hours)
 *   - But makes brute-force attacks extremely slow
 *
 * ─── WHAT HAPPENS ON REGISTER ────────────────────────────────────────────────
 *   1. Check if the email is already taken → reject with a clear error
 *   2. Hash the password with BCrypt
 *   3. Save the new User to PostgreSQL
 *   4. Generate a JWT for the new user → they're logged in immediately
 *
 * ─── WHAT HAPPENS ON LOGIN ───────────────────────────────────────────────────
 *   1. Find the user by email → reject if not found (generic message for security)
 *   2. Verify the submitted password against the stored BCrypt hash
 *   3. If matches → generate and return a JWT
 *   4. If wrong password → reject with generic "invalid credentials" message
 *      (never reveal *which* part was wrong — this prevents user enumeration)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    // BCrypt with strength 12 — strong and still fast enough for user-facing auth
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    @Value("${jwt.expiration-milliseconds}")
    private long tokenExpirationMilliseconds;

    /**
     * Registers a new user and returns a JWT so they're logged in immediately.
     *
     * @param registrationRequest Contains the desired email and password
     * @return AuthResponse with JWT token and user info
     */
    @Transactional
    public AuthResponse registerNewUser(RegisterRequest registrationRequest) {
        String requestedEmail = registrationRequest.getEmail().toLowerCase().trim();

        // Prevent duplicate accounts with the same email
        if (userRepository.existsByEmail(requestedEmail)) {
            throw new IllegalArgumentException("An account with this email already exists: " + requestedEmail);
        }

        String hashedPassword = passwordEncoder.encode(registrationRequest.getPassword());
        User newUser = new User(requestedEmail, hashedPassword);
        User savedUser = userRepository.save(newUser);

        log.info("New user registered: {} (ID: {})", savedUser.getEmail(), savedUser.getId());

        String jwtToken = jwtTokenProvider.generateToken(savedUser.getId(), savedUser.getEmail());
        return buildAuthResponse(savedUser, jwtToken);
    }

    /**
     * Authenticates an existing user and returns a fresh JWT.
     *
     * @param loginRequest Contains the user's email and password
     * @return AuthResponse with JWT token and user info
     */
    public AuthResponse loginExistingUser(LoginRequest loginRequest) {
        String submittedEmail = loginRequest.getEmail().toLowerCase().trim();

        // Generic error message — don't reveal whether the email or password was wrong
        String invalidCredentialsMessage = "Invalid email or password.";

        User existingUser = userRepository.findByEmail(submittedEmail)
                .orElseThrow(() -> new IllegalArgumentException(invalidCredentialsMessage));

        boolean passwordMatches = passwordEncoder.matches(loginRequest.getPassword(), existingUser.getPasswordHash());
        if (!passwordMatches) {
            log.warn("Failed login attempt for email: {}", submittedEmail);
            throw new IllegalArgumentException(invalidCredentialsMessage);
        }

        log.info("User logged in: {} (ID: {})", existingUser.getEmail(), existingUser.getId());

        String jwtToken = jwtTokenProvider.generateToken(existingUser.getId(), existingUser.getEmail());
        return buildAuthResponse(existingUser, jwtToken);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user, String jwtToken) {
        return AuthResponse.builder()
                .token(jwtToken)
                .expiresInMilliseconds(tokenExpirationMilliseconds)
                .userId(user.getId())
                .email(user.getEmail())
                .build();
    }
}
