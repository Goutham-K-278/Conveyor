package com.conveyor.cache;

import com.conveyor.job.JobResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Caches job status responses in Redis to reduce database load.
 *
 * ─── WHY WE CACHE ────────────────────────────────────────────────────────────
 * After a user submits a job, they typically poll GET /jobs/{id} repeatedly
 * until it completes (every 2-5 seconds for potentially minutes).
 * Without caching, each poll hits PostgreSQL — 100 users polling = 100 DB reads/sec.
 *
 * With this cache:
 *   - First call → DB hit → cache for 60 seconds
 *   - Next 59 seconds of polls → serve from Redis (memory) instead of PostgreSQL
 *   - After 60 seconds → TTL expires → next call refreshes from DB
 *
 * 60-second TTL is a good balance: short enough that status updates propagate
 * quickly (a completed job shows up within 1 minute), long enough to
 * meaningfully reduce DB load during active polling.
 *
 * ─── CACHE INVALIDATION ──────────────────────────────────────────────────────
 * We let the cache expire naturally via TTL rather than actively invalidating it.
 * Workers update the database; the next cache miss (after TTL) picks up the change.
 * This is simpler and sufficient given the 60-second TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobStatusCache {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${queue.job-cache-prefix}")
    private String jobCacheKeyPrefix;

    @Value("${queue.job-cache-ttl-seconds}")
    private long jobCacheTtlSeconds;

    // Jackson is used to serialize/deserialize JobResponse to/from a JSON string
    private final ObjectMapper objectMapper = buildObjectMapper();

    /**
     * Stores a JobResponse in Redis with a TTL of 60 seconds.
     * Silently ignores any serialization errors — caching is best-effort.
     *
     * @param jobId    The job ID (used as the cache key)
     * @param response The current response to cache
     */
    public void cacheJobStatus(Long jobId, JobResponse response) {
        String cacheKey = jobCacheKeyPrefix + jobId;
        try {
            String serializedResponse = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, serializedResponse, jobCacheTtlSeconds, TimeUnit.SECONDS);
            log.debug("Cached status for job {} (TTL: {}s)", jobId, jobCacheTtlSeconds);
        } catch (JsonProcessingException serializationError) {
            // Non-fatal — just means this request won't benefit from caching
            log.warn("Could not serialize job {} response for cache: {}", jobId, serializationError.getMessage());
        }
    }

    /**
     * Returns the cached JobResponse for a job, or null if not in cache.
     * Callers should treat a null return as "cache miss — go to the database."
     *
     * @param jobId The job ID to look up
     * @return The cached JobResponse, or null if expired / not found
     */
    public JobResponse getCachedJobStatus(Long jobId) {
        String cacheKey = jobCacheKeyPrefix + jobId;
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);

        if (cachedJson == null) {
            log.debug("Cache miss for job {}", jobId);
            return null;
        }

        try {
            log.debug("Cache hit for job {}", jobId);
            return objectMapper.readValue(cachedJson, JobResponse.class);
        } catch (JsonProcessingException deserializationError) {
            log.warn("Could not deserialize cached response for job {}: {}", jobId, deserializationError.getMessage());
            return null; // Treat bad cache data as a miss
        }
    }

    /**
     * Explicitly removes a job's cached status.
     * Called when a job's status changes (e.g. COMPLETED) to prevent
     * stale data being returned to the user.
     *
     * @param jobId The job whose cache entry should be cleared
     */
    public void evictCachedJobStatus(Long jobId) {
        String cacheKey = jobCacheKeyPrefix + jobId;
        redisTemplate.delete(cacheKey);
        log.debug("Evicted cache for job {}", jobId);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Register JavaTimeModule so LocalDateTime fields serialize correctly
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
