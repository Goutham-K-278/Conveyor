package com.conveyor.worker;

import com.conveyor.job.Job;
import com.conveyor.job.JobRepository;
import com.conveyor.job.JobStatus;
import com.conveyor.processing.ImageProcessor;
import com.conveyor.processing.ImageProcessingResult;
import com.conveyor.queue.DeadLetterQueuePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The core job processing loop that runs continuously on each worker instance.
 *
 * ─── WHAT THIS DOES ──────────────────────────────────────────────────────────
 * After the application starts, this loop runs on a background thread, endlessly:
 *   1. POLL   — blocks on Redis BRPOP waiting for a job ID to appear in the queue
 *   2. CLAIM  — atomically claims the job via JobClaimer (Redis lock + DB update)
 *   3. PROCESS — hands the job to ImageProcessor for the actual work
 *   4. ACK    — marks the job COMPLETED in the database
 *   5. REPEAT — immediately goes back to step 1
 *
 * If anything goes wrong in step 3, the job gets a retry attempt recorded.
 * If the worker crashes entirely (JVM kill), the StuckJobReaper handles recovery.
 *
 * ─── BRPOP vs POLLING ────────────────────────────────────────────────────────
 * We use Redis BRPOP (Blocking Right POP) instead of a busy polling loop.
 * BRPOP blocks the thread for up to 5 seconds waiting for a new job.
 * This means:
 *   - Zero CPU usage when the queue is empty (no wasteful polling)
 *   - ~0ms latency when a job arrives (immediate wakeup)
 *   - The 5s timeout lets us check for shutdown signals periodically
 *
 * ─── RETRY WITH EXPONENTIAL BACKOFF ─────────────────────────────────────────
 * When image processing fails, we don't immediately requeue the job.
 * We wait an increasing amount of time before the next attempt:
 *   Attempt 1 failed → wait 10s before release
 *   Attempt 2 failed → wait 20s before release
 *   Attempt 3 failed → wait 30s → StuckJobReaper eventually permanently fails it
 *
 * This backoff prevents hammering external resources (disk, GPU) with
 * rapid retries when they're experiencing a transient error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerLoop {

    private final JobClaimer jobClaimer;
    private final JobRepository jobRepository;
    private final ImageProcessor imageProcessor;
    private final DeadLetterQueuePublisher deadLetterQueuePublisher;

    @Value("${worker.worker-id:worker-default}")
    private String workerId;

    @Value("${worker.maximum-retry-attempts}")
    private int maximumRetryAttempts;

    @Value("${queue.job-queue-name}")
    private String jobQueueName;

    // How long BRPOP waits before returning empty (in seconds)
    private static final int BRPOP_TIMEOUT_SECONDS = 5;

    // Base delay in seconds between retry attempts (multiplied by attempt count)
    private static final int RETRY_BACKOFF_BASE_SECONDS = 10;

    private volatile boolean isShuttingDown = false;

    /**
     * Starts the blocking worker loop after the application is fully ready.
     * Runs on a separate thread so it doesn't block the Spring startup.
     *
     * Using ApplicationReadyEvent ensures all beans are wired before we start
     * polling Redis — important because the worker depends on many other beans.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startWorkerLoopOnApplicationReady() {
        log.info("Worker '{}' is ready. Starting job processing loop...", workerId);

        Thread workerThread = new Thread(this::runProcessingLoopUntilShutdown, "worker-loop-thread");
        workerThread.setDaemon(false); // Keep JVM alive while this thread runs
        workerThread.start();
    }

    /**
     * The main processing loop. Runs continuously until shutdown is signaled.
     * Designed to never throw an unhandled exception — any error is caught,
     * logged, and the loop continues.
     */
    private void runProcessingLoopUntilShutdown() {
        while (!isShuttingDown) {
            try {
                pollAndProcessOneJob();
            } catch (InterruptedException interruptSignal) {
                log.info("Worker '{}' loop interrupted — shutting down.", workerId);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception unexpectedError) {
                // Log but never crash the loop — keep processing other jobs
                log.error("Unexpected error in worker loop for '{}'. Continuing...", workerId, unexpectedError);
            }
        }

        log.info("Worker '{}' has stopped.", workerId);
    }

    /**
     * Pulls one job ID from Redis, claims it, processes it, and records the result.
     * If Redis is empty, BRPOP blocks for up to BRPOP_TIMEOUT_SECONDS then returns.
     */
    private void pollAndProcessOneJob() throws InterruptedException {
        // BRPOP blocks waiting for a job ID — timeout means the queue was empty
        String rawJobId = redisTemplate().opsForList()
                .rightPop(jobQueueName, BRPOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        if (rawJobId == null || rawJobId.isBlank()) {
            // Queue was empty — loop back and wait again
            return;
        }

        Long jobId = Long.parseLong(rawJobId);

        log.info("Worker '{}' dequeued job ID {}", workerId, jobId);
        processDequeudJob(jobId);
    }

    @Transactional
    private void processDequeudJob(Long jobId) {
        // ── Step 1: Load the job from the database ────────────────────────────
        Optional<Job> jobLookupResult = jobRepository.findById(jobId);
        if (jobLookupResult.isEmpty()) {
            log.warn("Worker '{}' dequeued job ID {} but it no longer exists in the database. Skipping.", workerId, jobId);
            return;
        }
        Job job = jobLookupResult.get();

        // ── Step 2: Atomically claim the job ──────────────────────────────────
        Optional<Job> claimResult = jobClaimer.claimJobExclusively(jobId, workerId);
        if (claimResult.isEmpty()) {
            log.debug("Worker '{}' failed to claim job {} — already taken. Skipping.", workerId, jobId);
            return;
        }
        Job claimedJob = claimResult.get();

        // ── Step 3: Process the image ─────────────────────────────────────────
        try {
            log.info("Worker '{}' starting image processing for job {}", workerId, jobId);
            ImageProcessingResult processingResult = imageProcessor.processImageJob(claimedJob);
            markJobAsCompleted(claimedJob, processingResult);

        } catch (Exception processingFailure) {
            log.error("Worker '{}' failed to process job {}: {}", workerId, jobId, processingFailure.getMessage(), processingFailure);
            handleProcessingFailure(claimedJob, processingFailure);

        } finally {
            // Always release the lock, whether we succeeded or failed
            jobClaimer.releaseJobLock(jobId);
        }
    }

    private void markJobAsCompleted(Job job, ImageProcessingResult result) {
        job.setStatus(JobStatus.COMPLETED);
        job.setOutputPath(result.getOutputPathsAsJson());
        job.setMetadata(result.getExtractedMetadata());
        jobRepository.save(job);
        log.info("Job {} completed successfully by worker '{}'", job.getId(), workerId);
    }

    private void handleProcessingFailure(Job job, Exception failureCause) {
        int newAttemptCount = job.getAttempts() + 1;
        boolean hasExhaustedAllRetries = newAttemptCount >= maximumRetryAttempts;

        if (hasExhaustedAllRetries) {
            // Move to permanently failed state
            String errorReason = "Processing failed after " + newAttemptCount + " attempts. Last error: " + failureCause.getMessage();
            job.setStatus(JobStatus.FAILED);
            job.setAttempts(newAttemptCount);
            job.setErrorReason(errorReason);
            job.setClaimedByWorkerId(null);
            job.setClaimedAt(null);
            jobRepository.save(job);
            deadLetterQueuePublisher.sendToDeadLetterQueue(job.getId(), errorReason);
            log.error("Job {} permanently failed after {} attempts.", job.getId(), newAttemptCount);
        } else {
            // Apply exponential backoff, then reset to PENDING for another worker to pick up
            applyRetryBackoff(newAttemptCount);
            job.setStatus(JobStatus.PENDING);
            job.setAttempts(newAttemptCount);
            job.setClaimedByWorkerId(null);
            job.setClaimedAt(null);
            jobRepository.save(job);
            log.warn("Job {} failed on attempt {}/{}. Reset to PENDING for retry.", job.getId(), newAttemptCount, maximumRetryAttempts);
        }
    }

    private void applyRetryBackoff(int attemptCount) {
        long backoffMilliseconds = (long) attemptCount * RETRY_BACKOFF_BASE_SECONDS * 1000;
        try {
            log.debug("Applying {}ms retry backoff before releasing job back to queue.", backoffMilliseconds);
            Thread.sleep(backoffMilliseconds);
        } catch (InterruptedException interruptSignal) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Shutdown Hook ─────────────────────────────────────────────────────────

    /**
     * Signals the worker loop to stop gracefully on application shutdown.
     * The loop checks isShuttingDown at the top of each iteration.
     */
    @jakarta.annotation.PreDestroy
    public void stopWorkerLoopOnShutdown() {
        log.info("Shutdown signal received for worker '{}'. Stopping loop...", workerId);
        isShuttingDown = true;
    }

    // ── Redis Template Access ─────────────────────────────────────────────────
    // Accessed via a method to avoid circular dependency issues during startup
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;

    private org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate() {
        return this.redisTemplate;
    }
}
