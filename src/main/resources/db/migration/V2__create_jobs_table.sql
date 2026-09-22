-- ─────────────────────────────────────────────────────────────────────────────
-- V2: Create the Jobs Table
-- ─────────────────────────────────────────────────────────────────────────────
-- This is the central table of Conveyor. Every background task is a "job" row.
-- Workers update this row as the job moves through its lifecycle.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE jobs (
    -- ── Identity ──────────────────────────────────────────────────────────
    id           BIGSERIAL    PRIMARY KEY,

    -- ── Ownership ─────────────────────────────────────────────────────────
    -- Which user submitted this job. NULL would be a bug — always required.
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- ── Job Classification ────────────────────────────────────────────────
    -- What kind of work this job does. Currently only IMAGE_PROCESS.
    type         VARCHAR(50)  NOT NULL,

    -- ── Lifecycle Status ──────────────────────────────────────────────────
    -- PENDING    = queued, waiting for a worker to pick it up
    -- PROCESSING = a worker has claimed it and is actively working
    -- COMPLETED  = done, result paths are in output_path
    -- FAILED     = exhausted all retries, see error_reason
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING',

    -- ── File Paths ────────────────────────────────────────────────────────
    input_path   TEXT         NOT NULL,
    output_path  TEXT,        -- NULL until job completes

    -- ── Processing Results ────────────────────────────────────────────────
    -- JSONB gives us flexible metadata storage: width, height, format, EXIF, etc.
    metadata     JSONB,

    -- ── Fault Tolerance Tracking ──────────────────────────────────────────
    -- How many times this job has been attempted (starts at 0)
    attempts     INT          NOT NULL DEFAULT 0,
    -- Human-readable error description, filled on failure
    error_reason TEXT,

    -- ── Worker Ownership ─────────────────────────────────────────────────
    -- Which worker instance claimed this job (e.g. "worker-hostname-abc123")
    claimed_by   VARCHAR(255),
    -- When the worker claimed it — used to detect stuck jobs
    claimed_at   TIMESTAMP,

    -- ── Timestamps ────────────────────────────────────────────────────────
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── Indexes for Common Queries ──────────────────────────────────────────────

-- Workers query by status + claimed_at to find stuck jobs — needs to be fast
CREATE INDEX index_jobs_on_status_and_claimed_at ON jobs (status, claimed_at);

-- API queries jobs by user — most common read pattern
CREATE INDEX index_jobs_on_user_id ON jobs (user_id);

-- Status-only index for queue publisher polling
CREATE INDEX index_jobs_on_status ON jobs (status);
