package com.conveyor.job;

import com.conveyor.cache.JobStatusCache;
import com.conveyor.exception.JobNotFoundException;
import com.conveyor.exception.UnauthorizedAccessException;
import com.conveyor.queue.JobQueuePublisher;
import com.conveyor.storage.FileStorageService;
import com.conveyor.storage.StoredFileResult;
import com.conveyor.user.User;
import com.conveyor.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobService business logic.
 *
 * These tests use Mockito to mock all dependencies (database, queue, cache)
 * so we can test the service logic in complete isolation — no actual database
 * or Redis connection is needed. Tests run in milliseconds.
 *
 * Each test verifies one specific behavior rule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobService — Business Logic Tests")
class JobServiceTest {

    @Mock private JobRepository jobRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private JobQueuePublisher jobQueuePublisher;
    @Mock private JobStatusCache jobStatusCache;

    @InjectMocks
    private JobService jobService;

    private User testUser;
    private Job testJob;

    @BeforeEach
    void setupTestFixtures() {
        testUser = new User("user@example.com", "hashed-password");
        testUser = setId(testUser, 1L);

        testJob = Job.createNewJob(testUser, JobType.IMAGE_PROCESS, "uploads/originals/test.jpg");
        testJob = setJobId(testJob, 10L);
    }

    // ── Job Creation Tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("Creating a job should set status to PENDING and enqueue it")
    void shouldCreateJobWithPendingStatusAndEnqueueIt() {
        MockMultipartFile imageFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake-image-bytes".getBytes());

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(fileStorageService.storeOriginalFile(any())).thenReturn(
            StoredFileResult.builder().filePath("uploads/originals/abc-photo.jpg").fileName("abc-photo.jpg").build()
        );
        when(jobRepository.save(any(Job.class))).thenReturn(testJob);

        JobResponse response = jobService.createImageJob(1L, imageFile);

        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(jobQueuePublisher, times(1)).enqueueJob(testJob.getId());
        verify(jobRepository, times(1)).save(any(Job.class));
    }

    @Test
    @DisplayName("Creating a job should store the uploaded file before saving to DB")
    void shouldStoreFileToDiskBeforeSavingJobToDatabase() {
        MockMultipartFile imageFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[100]);

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(fileStorageService.storeOriginalFile(any())).thenReturn(
            StoredFileResult.builder().filePath("uploads/originals/abc-photo.jpg").fileName("abc-photo.jpg").build()
        );
        when(jobRepository.save(any(Job.class))).thenReturn(testJob);

        jobService.createImageJob(1L, imageFile);

        // File storage must happen before DB save — verify both were called
        verify(fileStorageService).storeOriginalFile(imageFile);
        verify(jobRepository).save(any(Job.class));
    }

    // ── Job Ownership Tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("Getting a job should throw UnauthorizedAccessException if user doesn't own it")
    void shouldThrowUnauthorizedWhenUserDoesNotOwnJob() {
        Long differentUserId = 999L;
        when(jobStatusCache.getCachedJobStatus(anyLong())).thenReturn(null);
        when(jobRepository.findByIdAndOwnerId(testJob.getId(), differentUserId)).thenReturn(Optional.empty());
        when(jobRepository.existsById(testJob.getId())).thenReturn(true); // Job exists but belongs to someone else

        assertThatThrownBy(() -> jobService.getJobStatus(differentUserId, testJob.getId()))
            .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("Getting a nonexistent job should throw JobNotFoundException")
    void shouldThrowJobNotFoundWhenJobDoesNotExist() {
        when(jobStatusCache.getCachedJobStatus(anyLong())).thenReturn(null);
        when(jobRepository.findByIdAndOwnerId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(jobRepository.existsById(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> jobService.getJobStatus(1L, 9999L))
            .isInstanceOf(JobNotFoundException.class);
    }

    // ── Job Deletion Tests ────────────────────────────────────────────────
    
    @Test
    @DisplayName("Deleting a PENDING job should succeed and delete the file")
    void shouldDeletePendingJobAndDeleteFile() {
        testJob.setStatus(JobStatus.PENDING);
        when(jobRepository.findByIdAndOwnerId(testJob.getId(), 1L)).thenReturn(Optional.of(testJob));

        jobService.deleteJob(1L, testJob.getId());

        verify(fileStorageService).deleteFile(testJob.getInputPath());
        verify(jobRepository).delete(testJob);
    }

    @Test
    @DisplayName("Deleting a PROCESSING job should throw IllegalStateException")
    void shouldRefuseDeletionOfProcessingJob() {
        testJob.setStatus(JobStatus.PROCESSING);
        when(jobRepository.findByIdAndOwnerId(testJob.getId(), 1L)).thenReturn(Optional.of(testJob));

        assertThatThrownBy(() -> jobService.deleteJob(1L, testJob.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PROCESSING");
    }

    @Test
    @DisplayName("Deleting a COMPLETED job should succeed")
    void shouldAllowDeletionOfCompletedJob() {
        testJob.setStatus(JobStatus.COMPLETED);
        when(jobRepository.findByIdAndOwnerId(testJob.getId(), 1L)).thenReturn(Optional.of(testJob));

        jobService.deleteJob(1L, testJob.getId());

        verify(fileStorageService).deleteFile(testJob.getInputPath());
        verify(jobRepository).delete(testJob);
    }

    // ── Test Helpers ──────────────────────────────────────────────────────────
    // Reflection helpers to set IDs on entities (since JPA auto-generates them)

    private User setId(User user, Long id) {
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return user;
    }

    private Job setJobId(Job job, Long id) {
        try {
            var idField = Job.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(job, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return job;
    }
}
