package com.conveyor.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes job IDs into the Redis job queue.
 *
 * The queue is implemented as a Redis List:
 *   - The API pushes to the LEFT side: LPUSH conveyor:job_queue {job_id}
 *   - Workers pull from the RIGHT side: BRPOP conveyor:job_queue
 *   - This is a FIFO queue — oldest job gets processed first
 *
 * We only push the job ID (a number), not the full job object.
 * Workers load the full job from PostgreSQL after dequeuing.
 * This keeps the queue lightweight and prevents stale data issues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobQueuePublisher {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${queue.job-queue-name}")
    private String jobQueueName;

    /**
     * Pushes a job ID onto the queue after the API creates a new job.
     *
     * @param jobId The database ID of the newly created job
     */
    public void enqueueJob(Long jobId) {
        String jobIdAsString = String.valueOf(jobId);
        redisTemplate.opsForList().leftPush(jobQueueName, jobIdAsString);
        log.info("Enqueued job ID {} onto queue '{}'", jobId, jobQueueName);
    }

    /**
     * Pushes a job ID back onto the queue after a stuck-job is detected.
     * This is functionally identical to enqueueJob — it's a separate method
     * for clarity in logs and to make the intent explicit.
     *
     * @param jobId The database ID of the job being requeued
     */
    public void requeueStuckJob(Long jobId) {
        String jobIdAsString = String.valueOf(jobId);
        redisTemplate.opsForList().leftPush(jobQueueName, jobIdAsString);
        log.info("Requeued stuck job ID {} back onto queue '{}'", jobId, jobQueueName);
    }
}
