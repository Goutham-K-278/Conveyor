package com.conveyor.worker;

import com.conveyor.job.Job;
import com.conveyor.job.JobRepository;
import com.conveyor.job.JobStatus;
import com.conveyor.queue.DeadLetterQueuePublisher;
import com.conveyor.queue.JobQueuePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The Stuck-Job Reaper — the heart of Conveyor's fault tolerance.
 *
 * ─── THE PROBLEM IT SOLVES ────────────────────────────────────────────────────
 * Workers claim jobs and process them. But what if a worker crashes mid-job?
 *
 * Example scenario:
 *   1. Worker-1 claims job #42 at 10:00:00 → sets status=PROCESSING, claimed_at=10:00:00
 *   2. Worker-1 is halfway through resizing the image...
 *   3. Worker-1 gets an OutOfMemoryError and its JVM crashes
 *   4. Nobody updates job #42 → it stays PROCESSING forever
 *   5. User checks status → sees PROCESSING indefinitely → never gets their result
 *
 * ─── THE SOLUTION ─────────────────────────────────────────────────────────────
 * This reaper runs every 30 seconds and asks PostgreSQL:
 *   "Give me all jobs where status = PROCESSING AND claimed_at < (now - 2 minutes)"
 *
 * For each stuck job found:
 *   CASE A: attempts < maximum_retry_attempts
 *     → Increment attempts counter
 *     → Reset status to PENDING
 *     → Clear claimed_by and claimed_at
 *     → Push job ID back onto the Redis queue
 *     → A healthy worker will pick it up and try again
 *
 *   CASE B: attempts >= maximum_retry_attempts
 *     → Move job to FAILED status
 *     → Set a clear error_reason message
 *     → Push to the dead-letter queue for human inspection
 *     → Give up — some jobs simply cannot be processed
 *
 * ─── WHY 2 MINUTES? ───────────────────────────────────────────────────────────
 * We set the stuck timeout to 2 minutes, which is much longer than a typical
 * image processing operation (a few seconds). This avoids false positives —
 * a healthy worker processing a large image won't be mistaken for a crashed one.
 * For much longer operations, you'd increase this window accordingly.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class StuckJobReaper {

    private final JobRepository jobRepository;
    private final JobQueuePublisher jobQueuePublisher;
    private final DeadLetterQueuePublisher deadLetterQueuePublisher;

    @Value("${worker.maximum-retry-attempts}")
    private int maximumRetryAttempts;

    @Value("${worker.stuck-job-timeout-minutes}")
    private int stuckJobTimeoutMinutes;

    /**
     * Runs on a fixed schedule (default: every 30 seconds).
     * Detects all stuck jobs and either requeues them or permanently fails them.
     */
    @Scheduled(fixedDelayString = "${worker.reaper-check-interval-milliseconds}")
    @Transactional
    public void detectAndRecoverStuckJobs() {
        LocalDateTime stuckDeadline = LocalDateTime.now().minusMinutes(stuckJobTimeoutMinutes);
        List<Job> stuckJobs = jobRepository.findJobsStuckInProcessing(stuckDeadline);

        if (stuckJobs.isEmpty()) {
            log.debug("Stuck-job reaper found no stuck jobs.");
            return;
        }

        log.warn("Stuck-job reaper found {} stuck job(s). Recovering...", stuckJobs.size());

        for (Job stuckJob : stuckJobs) {
            recoverOrFailStuckJob(stuckJob);
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void recoverOrFailStuckJob(Job stuckJob) {
        boolean hasRemainingRetryAttempts = stuckJob.getAttempts() < maximumRetryAttempts;

        if (hasRemainingRetryAttempts) {
            requeueJobForRetry(stuckJob);
        } else {
            permanentlyFailJob(stuckJob);
        }
    }

    private void requeueJobForRetry(Job stuckJob) {
        log.warn(
            "Job {} was stuck (claimed by '{}' at {}). Attempt {}/{} — requeuing for retry.",
            stuckJob.getId(),
            stuckJob.getClaimedByWorkerId(),
            stuckJob.getClaimedAt(),
            stuckJob.getAttempts(),
            maximumRetryAttempts
        );

        // Reset the job to a clean PENDING state
        stuckJob.setStatus(JobStatus.PENDING);
        stuckJob.setAttempts(stuckJob.getAttempts() + 1);
        stuckJob.setClaimedByWorkerId(null);
        stuckJob.setClaimedAt(null);
        jobRepository.save(stuckJob);

        // Push the job ID back onto the Redis queue for a worker to pick up
        jobQueuePublisher.requeueStuckJob(stuckJob.getId());
    }

    private void permanentlyFailJob(Job stuckJob) {
        String failureReason = String.format(
            "Job exceeded maximum retry attempts (%d/%d). Last claimed by worker '%s'. Permanently failed.",
            stuckJob.getAttempts(),
            maximumRetryAttempts,
            stuckJob.getClaimedByWorkerId()
        );

        log.error("Job {} permanently failed: {}", stuckJob.getId(), failureReason);

        stuckJob.setStatus(JobStatus.FAILED);
        stuckJob.setErrorReason(failureReason);
        stuckJob.setClaimedByWorkerId(null);
        stuckJob.setClaimedAt(null);
        jobRepository.save(stuckJob);

        // Push to the dead-letter queue for human inspection / alerting
        deadLetterQueuePublisher.sendToDeadLetterQueue(stuckJob.getId(), failureReason);
    }
}
