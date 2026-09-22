-- ─────────────────────────────────────────────────────────────────────────────
-- V1: Create the Users Table
-- ─────────────────────────────────────────────────────────────────────────────
-- Stores registered users. Each user can own many jobs.
-- Passwords are stored as bcrypt hashes — never plaintext.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id           BIGSERIAL    PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Index on email because every login/auth check looks up by email
CREATE INDEX index_users_on_email ON users (email);
