package com.conveyor.worker;

import com.conveyor.job.Job;
import com.conveyor.job.JobRepository;
import com.conveyor.job.JobStatus;
import com.conveyor.job.JobType;
import com.conveyor.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobClaimer — the atomic job claiming mechanism.
 *
 * These tests verify the two-layer claiming strategy:
 *   Layer 1: Redis SETNX (only one worker wins the lock)
 *   Layer 2: Database conditional UPDATE (status = PENDING guard)
 *
 * This is the most critical logic in Conveyor — if it breaks,
 * two workers could process the same job simultaneously.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobClaimer — Atomic Claiming Tests")
class JobClaimerTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private JobRepository jobRepository;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private JobClaimer jobClaimer;

    private Job pendingJob;

    @BeforeEach
    void setupTestFixtures() {
        ReflectionTestUtils.setField(jobClaimer, "jobLockKeyPrefix", "conveyor:lock:");
        ReflectionTestUtils.setField(jobClaimer, "jobLockTtlSeconds", 180L);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        User testUser = new User("test@test.com", "hash");
        pendingJob = Job.createNewJob(testUser, JobType.IMAGE_PROCESS, "uploads/originals/test.jpg");
        // Set ID via reflection since JPA would normally assign it
        ReflectionTestUtils.setField(pendingJob, "id", 42L);
    }

    @Test
    @DisplayName("First worker to call SETNX should successfully claim the job")
    void shouldSuccessfullyClaimJobWhenRedisLockIsAvailable() {
        // Redis returns true = SETNX won (lock was not held)
        when(valueOperations.setIfAbsent(eq("conveyor:lock:42"), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
            .thenReturn(true);
        // Database UPDATE returns 1 = row was updated (job was still PENDING)
        when(jobRepository.claimJobAtomically(eq(42L), anyString(), any())).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(pendingJob));

        Optional<Job> claimResult = jobClaimer.claimJobExclusively(42L, "worker-1");

        assertThat(claimResult).isPresent();
        assertThat(claimResult.get().getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Second worker attempting to claim the same job should get empty result")
    void shouldReturnEmptyWhenRedisLockIsAlreadyHeldByAnotherWorker() {
        // Redis returns false = SETNX lost (lock already held by worker-1)
        when(valueOperations.setIfAbsent(eq("conveyor:lock:42"), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
            .thenReturn(false);

        Optional<Job> claimResult = jobClaimer.claimJobExclusively(42L, "worker-2");

        assertThat(claimResult).isEmpty();
        // Should NOT have touched the database at all — Redis was the fast gate
        verify(jobRepository, never()).claimJobAtomically(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("If Redis lock passes but DB update returns 0 rows, claim should fail")
    void shouldReturnEmptyWhenDatabaseClaimFailsAfterRedisLockSucceeds() {
        // Redis lock succeeds
        when(valueOperations.setIfAbsent(eq("conveyor:lock:42"), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
            .thenReturn(true);
        // But database UPDATE returns 0 = job was already PROCESSING (another worker was faster)
        when(jobRepository.claimJobAtomically(eq(42L), anyString(), any())).thenReturn(0);

        Optional<Job> claimResult = jobClaimer.claimJobExclusively(42L, "worker-2");

        assertThat(claimResult).isEmpty();
        // Redis lock should be released immediately since we failed at the DB level
        verify(redisTemplate).delete("conveyor:lock:42");
    }

    @Test
    @DisplayName("Releasing a lock should delete the Redis key")
    void shouldDeleteRedisKeyWhenLockIsReleased() {
        jobClaimer.releaseJobLock(42L);

        verify(redisTemplate).delete("conveyor:lock:42");
    }
}
