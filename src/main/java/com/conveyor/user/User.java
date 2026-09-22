package com.conveyor.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Represents a registered user of the Conveyor platform.
 *
 * Each user has their own isolated set of jobs — they can only
 * see and manage jobs they created.
 *
 * The password is stored as a bcrypt hash — the plaintext is never saved.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user's login email — must be unique across the system */
    @Column(nullable = false, unique = true)
    private String email;

    /** Bcrypt-hashed password — never store or log the raw password */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** When this account was created */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Automatically set createdAt before the first database insert */
    @PrePersist
    private void setCreatedAtBeforeInsert() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Convenience constructor for creating new users during registration.
     * The @Id and createdAt fields are set by JPA and @PrePersist automatically.
     */
    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
