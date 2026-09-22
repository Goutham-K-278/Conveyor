package com.conveyor.worker;

import com.conveyor.job.Job;
import com.conveyor.job.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Atomically claims a job from the queue for a specific worker instance.
 *
 * ─── THE CORE PROBLEM THIS SOLVES ────────────────────────────────────────────
 * When multiple workers are running in parallel, they all receive the same
 * job ID from the Redis queue. Without claiming, two workers could both start
 * processing job #123 simultaneously, causing:
 *   - Duplicate file writes (corruption)
 *   - Conflicting database updates
 *   - Double-billed compute resources
 *
 * ─── HOW WE PREVENT THIS: Two-Layer Atomic Claiming ─────────────────────────
 *
 * Layer 1 — Redis SETNX Lock:
 *   We use Redis SET NX (Set if Not eXists) with a TTL (time-to-live).
 *   Only the first worker to call SETNX wins. All others get back "false"
 *   and skip this job immediately. This is an O(1) Redis operation.
 *
 * Layer 2 — Database Conditional UPDATE:
 *   The winning worker then runs:
 *     UPDATE jobs SET status = 'PROCESSING' WHERE id = ? AND status = 'PENDING'
 *   If another worker somehow gets past Layer 1, the database is the final
 *   safety net — only one UPDATE can change a PENDING row to PROCESSING.
 *
 * ─── LOCK TTL (Time-To-Live) ─────────────────────────────────────────────────
 *   The lock has a TTL (default: 180 seconds). If a worker crashes, the Redis
 *   lock automatically expires. The StuckJobReaper then detects the stuck job
 *   via the database claimed_at timestamp and requeues it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobClaimer {

    private final RedisTemplate<String, String> redisTemplate;
    private final JobRepository jobRepository;

    @Value("${queue.job-lock-prefix}")
    private String jobLockKeyPrefix;

    @Value("${queue.job-lock-ttl-seconds}")
    private long jobLockTtlSeconds;

    /**
     * Attempts to exclusively claim a job for processing.
     *
     * Returns the claimed Job if successful, or empty() if another worker
     * already claimed it. The caller should skip to the next job if empty.
     *
     * @param jobId    The ID of the job to claim
     * @param workerId The unique identifier of this worker instance
     * @return Optional containing the claimed Job, or empty if claim failed
     */
    @Transactional
    public Optional<Job> claimJobExclusively(Long jobId, String workerId) {
        String redisLockKey = jobLockKeyPrefix + jobId;

        // ── Step 1: Acquire the Redis SETNX lock ─────────────────────────────
        // SET key value NX EX seconds
        //   NX = only set if key does NOT exist
        //   EX = set expiry in seconds
        // Returns true if we won the lock, false if another worker holds it.
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(redisLockKey, workerId, jobLockTtlSeconds, TimeUnit.SECONDS);

        if (lockAcquired == null || !lockAcquired) {
            log.debug("Worker {} could not acquire lock for job {} — another worker holds it", workerId, jobId);
            return Optional.empty();
        }

        log.debug("Worker {} acquired Redis lock for job {}", workerId, jobId);

        // ── Step 2: Atomically update the database row ────────────────────────
        // This is the database-level safety net. Even if two workers somehow
        // both acquired the Redis lock (e.g. due to Redis replication lag),
        // only one UPDATE against status = 'PENDING' can win.
        int rowsUpdated = jobRepository.claimJobAtomically(jobId, workerId, LocalDateTime.now());

        if (rowsUpdated == 0) {
            // The job was already claimed or no longer in PENDING state.
            // Release the Redis lock immediately since we're not processing.
            redisTemplate.delete(redisLockKey);
            log.debug("Worker {} lost database claim for job {} — job was already taken or not PENDING", workerId, jobId);
            return Optional.empty();
        }

        // Both checks passed — we own this job exclusively.
        Job claimedJob = jobRepository.findById(jobId).orElseThrow();
        log.info("Worker {} successfully claimed job {}", workerId, jobId);
        return Optional.of(claimedJob);
    }

    /**
     * Releases the Redis lock for a job once processing is complete (or failed).
     * This is a cleanup step — the lock would expire on its own via TTL,
     * but releasing it immediately frees up Redis memory sooner.
     *
     * @param jobId The ID of the job whose lock should be released
     */
    public void releaseJobLock(Long jobId) {
        String redisLockKey = jobLockKeyPrefix + jobId;
        redisTemplate.delete(redisLockKey);
        log.debug("Released Redis lock for job {}", jobId);
    }
}
