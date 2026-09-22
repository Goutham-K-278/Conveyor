package com.conveyor.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Database access for the Job entity.
 *
 * This repository contains queries for three distinct use cases:
 *   1. API queries — find jobs by user (for listing and status checks)
 *   2. Worker queries — find and update jobs during processing
 *   3. Reaper queries — find stuck jobs for the fault-tolerance watchdog
 */
@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    // ── API Queries ───────────────────────────────────────────────────────────

    /**
     * Returns all jobs belonging to a specific user, most recent first.
     * The API uses this for the "list my jobs" endpoint.
     */
    Page<Job> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    /**
     * Finds a specific job that belongs to a specific user.
     * The ownership check prevents users from seeing each other's jobs.
     */
    Optional<Job> findByIdAndOwnerId(Long jobId, Long ownerId);

    // ── Fault Tolerance / Reaper Queries ─────────────────────────────────────

    /**
     * Finds all jobs that are stuck in PROCESSING and haven't been updated
     * since the given deadline timestamp.
     *
     * This is the core query of the StuckJobReaper. A job is "stuck" when:
     *   - It's in PROCESSING status (a worker claimed it)
     *   - AND claimed_at is older than (now - stuck_timeout_minutes)
     *   - This means the worker likely crashed and will never finish
     *
     * The reaper then either requeues the job (if under max retries)
     * or permanently fails it (if retries are exhausted).
     */
    @Query("""
        SELECT job FROM Job job
        WHERE job.status = 'PROCESSING'
          AND job.claimedAt < :stuckDeadline
        """)
    List<Job> findJobsStuckInProcessing(@Param("stuckDeadline") LocalDateTime stuckDeadline);

    /**
     * Atomically claims a job by updating its status to PROCESSING and recording
     * the worker ID + claim timestamp — but ONLY IF the job is still PENDING.
     *
     * This is a critical safety mechanism: by including "WHERE status = PENDING"
     * in the UPDATE, we ensure only one worker can ever claim a given job,
     * even when multiple workers run the query simultaneously.
     *
     * Returns the number of rows updated (1 = claimed successfully, 0 = already taken).
     */
    @Modifying
    @Query("""
        UPDATE Job job
        SET job.status = 'PROCESSING',
            job.claimedByWorkerId = :workerId,
            job.claimedAt = :claimedAt
        WHERE job.id = :jobId
          AND job.status = 'PENDING'
        """)
    int claimJobAtomically(
            @Param("jobId") Long jobId,
            @Param("workerId") String workerId,
            @Param("claimedAt") LocalDateTime claimedAt
    );
}
