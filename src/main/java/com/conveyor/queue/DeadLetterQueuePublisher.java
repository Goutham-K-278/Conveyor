package com.conveyor.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends permanently failed jobs to the Dead-Letter Queue (DLQ).
 *
 * A job reaches the DLQ when it has exhausted all retry attempts.
 * Unlike the regular job queue, the DLQ is not automatically processed —
 * it exists for manual inspection, alerting, and debugging.
 *
 * In a production system, you'd have a monitoring job that reads the DLQ
 * and sends alerts to your team's Slack channel or PagerDuty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueuePublisher {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${queue.dead-letter-queue-name}")
    private String deadLetterQueueName;

    /**
     * Pushes a failed job's details to the dead-letter queue.
     *
     * The format stored is: "{jobId}|{reason}"
     * This keeps it readable via redis-cli without needing a JSON parser.
     *
     * @param jobId  The ID of the permanently failed job
     * @param reason A human-readable description of why the job permanently failed
     */
    public void sendToDeadLetterQueue(Long jobId, String reason) {
        String deadLetterEntry = jobId + "|" + reason;
        redisTemplate.opsForList().leftPush(deadLetterQueueName, deadLetterEntry);
        log.warn("Job ID {} moved to dead-letter queue. Reason: {}", jobId, reason);
    }
}
