package com.conveyor.worker;

import com.conveyor.job.Job;
import com.conveyor.job.JobRepository;
import com.conveyor.job.JobStatus;
import com.conveyor.job.JobType;
import com.conveyor.queue.DeadLetterQueuePublisher;
import com.conveyor.queue.JobQueuePublisher;
import com.conveyor.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StuckJobReaper — the fault-tolerance watchdog.
 *
 * Tests verify:
 *   - Stuck jobs under max retries are requeued (status reset to PENDING)
 *   - Stuck jobs at max retries are permanently failed (status = FAILED + DLQ)
 *   - Claim metadata (claimed_by, claimed_at) is cleared on requeue
 *   - Dead-letter queue receives correct job ID and reason
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StuckJobReaper — Fault Tolerance Tests")
class StuckJobReaperTest {

    @Mock private JobRepository jobRepository;
    @Mock private JobQueuePublisher jobQueuePublisher;
    @Mock private DeadLetterQueuePublisher deadLetterQueuePublisher;

    @InjectMocks
    private StuckJobReaper stuckJobReaper;

    @BeforeEach
    void configureReaperSettings() {
        ReflectionTestUtils.setField(stuckJobReaper, "maximumRetryAttempts", 3);
        ReflectionTestUtils.setField(stuckJobReaper, "stuckJobTimeoutMinutes", 2);
    }

    @Test
    @DisplayName("Stuck job with remaining retries should be reset to PENDING and requeued")
    void shouldResetJobToPendingAndRequeueWhenRetriesRemain() {
        Job stuckJob = createStuckJob(42L, 1, "worker-crashed"); // attempt 1 of 3

        when(jobRepository.findJobsStuckInProcessing(any(LocalDateTime.class)))
            .thenReturn(List.of(stuckJob));

        stuckJobReaper.detectAndRecoverStuckJobs();

        // Verify status reset
        ArgumentCaptor<Job> savedJobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(savedJobCaptor.capture());
        Job savedJob = savedJobCaptor.getValue();

        assertThat(savedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(savedJob.getAttempts()).isEqualTo(2); // incremented
        assertThat(savedJob.getClaimedByWorkerId()).isNull(); // cleared
        assertThat(savedJob.getClaimedAt()).isNull(); // cleared

        // Verify requeued
        verify(jobQueuePublisher).requeueStuckJob(42L);
        // NOT sent to dead-letter
        verify(deadLetterQueuePublisher, never()).sendToDeadLetterQueue(anyLong(), anyString());
    }

    @Test
    @DisplayName("Stuck job that has exhausted all retries should be permanently FAILED and sent to DLQ")
    void shouldPermanentlyFailJobAndSendToDeadLetterQueueWhenRetriesExhausted() {
        Job stuckJob = createStuckJob(99L, 3, "worker-oom"); // attempt 3 = max

        when(jobRepository.findJobsStuckInProcessing(any(LocalDateTime.class)))
            .thenReturn(List.of(stuckJob));

        stuckJobReaper.detectAndRecoverStuckJobs();

        ArgumentCaptor<Job> savedJobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(savedJobCaptor.capture());
        Job savedJob = savedJobCaptor.getValue();

        assertThat(savedJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(savedJob.getErrorReason()).isNotBlank();
        assertThat(savedJob.getClaimedByWorkerId()).isNull();

        // NOT requeued to job queue
        verify(jobQueuePublisher, never()).requeueStuckJob(anyLong());
        // Sent to dead-letter queue
        verify(deadLetterQueuePublisher).sendToDeadLetterQueue(eq(99L), anyString());
    }

    @Test
    @DisplayName("Reaper should do nothing when no stuck jobs exist")
    void shouldDoNothingWhenNoJobsAreStuck() {
        when(jobRepository.findJobsStuckInProcessing(any(LocalDateTime.class)))
            .thenReturn(List.of());

        stuckJobReaper.detectAndRecoverStuckJobs();

        verify(jobRepository, never()).save(any());
        verify(jobQueuePublisher, never()).requeueStuckJob(anyLong());
        verify(deadLetterQueuePublisher, never()).sendToDeadLetterQueue(anyLong(), anyString());
    }

    @Test
    @DisplayName("Reaper handles multiple stuck jobs in one sweep")
    void shouldRecoverAllStuckJobsInSingleRun() {
        Job stuckJobA = createStuckJob(1L, 0, "worker-1");
        Job stuckJobB = createStuckJob(2L, 0, "worker-2");

        when(jobRepository.findJobsStuckInProcessing(any(LocalDateTime.class)))
            .thenReturn(List.of(stuckJobA, stuckJobB));

        stuckJobReaper.detectAndRecoverStuckJobs();

        verify(jobRepository, times(2)).save(any(Job.class));
        verify(jobQueuePublisher).requeueStuckJob(1L);
        verify(jobQueuePublisher).requeueStuckJob(2L);
    }

    // ── Test Helpers ──────────────────────────────────────────────────────────

    private Job createStuckJob(Long id, int attempts, String claimedByWorkerId) {
        User user = new User("test@test.com", "hash");
        Job job = Job.createNewJob(user, JobType.IMAGE_PROCESS, "uploads/originals/test.jpg");
        ReflectionTestUtils.setField(job, "id", id);
        job.setStatus(JobStatus.PROCESSING);
        job.setAttempts(attempts);
        job.setClaimedByWorkerId(claimedByWorkerId);
        job.setClaimedAt(LocalDateTime.now().minusMinutes(10)); // definitely past the 2-minute timeout
        return job;
    }
}
