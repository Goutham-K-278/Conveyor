package com.conveyor.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Database access for the User entity.
 *
 * Spring Data JPA automatically generates the SQL queries based on method names.
 * We only need to define the custom lookup methods we actually use.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by their email address.
     * Used during login to verify credentials and during registration to
     * check if the email is already taken.
     *
     * Generated SQL: SELECT * FROM users WHERE email = ?
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether an account with this email already exists.
     * Used during registration to give a clear "email already in use" error.
     *
     * Generated SQL: SELECT COUNT(*) > 0 FROM users WHERE email = ?
     */
    boolean existsByEmail(String email);
}
