package com.conveyor.job;

/**
 * Represents every possible lifecycle state a job can be in.
 *
 * The normal happy path flows in one direction:
 *   PENDING → PROCESSING → COMPLETED
 *
 * If something goes wrong:
 *   PROCESSING → PENDING (requeued by the stuck-job reaper after a crash)
 *   PENDING    → FAILED  (after MAX_ATTEMPTS retries are exhausted)
 */
public enum JobStatus {

    /**
     * The job has been created and is sitting in the Redis queue,
     * waiting for a free worker to pick it up.
     */
    PENDING,

    /**
     * A worker has atomically claimed this job and is actively working on it.
     * The claimed_at timestamp is set here — if the worker crashes, the reaper
     * will detect this job as "stuck" after a timeout and reset it to PENDING.
     */
    PROCESSING,

    /**
     * The worker finished successfully. The output_path and metadata columns
     * in the database are now populated with the results.
     */
    COMPLETED,

    /**
     * The job exceeded the maximum number of retry attempts.
     * The error_reason column explains what went wrong.
     * The job has also been pushed to the dead-letter queue for inspection.
     */
    FAILED
}
