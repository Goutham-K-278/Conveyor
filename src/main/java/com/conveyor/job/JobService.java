package com.conveyor.job;

import com.conveyor.cache.JobStatusCache;
import com.conveyor.exception.JobNotFoundException;
import com.conveyor.exception.UnauthorizedAccessException;
import com.conveyor.queue.JobQueuePublisher;
import com.conveyor.storage.FileStorageService;
import com.conveyor.storage.StoredFileResult;
import com.conveyor.user.User;
import com.conveyor.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Business logic for all job-related operations.
 *
 * This service is the gatekeeper between the HTTP layer (JobController)
 * and the database/queue layer. It enforces:
 *   - Ownership: users can only access their own jobs
 *   - State transitions: you can only cancel a PENDING job
 *   - Caching: job status reads go through Redis cache first
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final JobQueuePublisher jobQueuePublisher;
    private final JobStatusCache jobStatusCache;

    /**
     * Creates a new image processing job for the authenticated user.
     *
     * Flow:
     *   1. Store the uploaded file on disk → get back a stored path
     *   2. Create a Job entity in the database (status = PENDING, attempts = 0)
     *   3. Push the job ID onto the Redis queue
     *   4. Return immediately — user does NOT wait for processing
     *
     * @param userId       The authenticated user's ID (from JWT)
     * @param uploadedFile The image file uploaded via multipart form
     * @return A response with the new job ID and PENDING status
     */
    @Transactional
    public JobResponse createImageJob(Long userId, MultipartFile uploadedFile) {
        // Load the user entity — we need it for the FK relationship
        User jobOwner = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found: " + userId));

        // Save the file to disk before creating the database record
        StoredFileResult storedFile = fileStorageService.storeOriginalFile(uploadedFile);

        // Create the job with PENDING status and zero attempts
        Job newJob = Job.createNewJob(jobOwner, JobType.IMAGE_PROCESS, storedFile.getFilePath());
        Job savedJob = jobRepository.save(newJob);

        // Push just the job ID onto the queue AFTER the transaction commits
        // This prevents workers from picking up the ID from Redis before the DB record exists
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobQueuePublisher.enqueueJob(savedJob.getId());
            }
        });

        log.info("Created job {} for user {} — queued for processing", savedJob.getId(), userId);
        return JobResponse.fromJob(savedJob);
    }

    /**
     * Returns the current status and results for a specific job.
     *
     * Checks the Redis cache first — if the status was recently fetched,
     * we return the cached response without touching the database.
     * Cache miss falls through to the database and updates the cache.
     *
     * @param userId The authenticated user's ID (ownership check)
     * @param jobId  The job to look up
     */
    public JobResponse getJobStatus(Long userId, Long jobId) {
        // ── Cache lookup ──────────────────────────────────────────────────────
        JobResponse cachedResponse = jobStatusCache.getCachedJobStatus(jobId);
        if (cachedResponse != null) {
            // Verify ownership even on cache hit — never let users see cached data for other users' jobs
            if (!cachedResponse.getJobId().equals(jobId)) {
                throw new UnauthorizedAccessException(jobId);
            }
            return cachedResponse;
        }

        // ── Database lookup ───────────────────────────────────────────────────
        Job job = jobRepository.findByIdAndOwnerId(jobId, userId)
                .orElseThrow(() -> {
                    // Check if the job exists at all to give the right error
                    boolean jobExists = jobRepository.existsById(jobId);
                    return jobExists
                        ? new UnauthorizedAccessException(jobId)
                        : new JobNotFoundException(jobId);
                });

        JobResponse response = JobResponse.fromJob(job);

        // Cache the result for frequent pollers
        jobStatusCache.cacheJobStatus(jobId, response);
        return response;
    }

    /**
     * Returns all jobs belonging to the authenticated user, most recent first.
     *
     * @param userId     The authenticated user's ID
     * @param pageNumber Which page to fetch (0-indexed)
     * @param pageSize   Number of jobs per page
     */
    public JobListResponse listUserJobs(Long userId, int pageNumber, int pageSize) {
        Pageable pageRequest = PageRequest.of(pageNumber, pageSize);
        Page<Job> jobPage = jobRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId, pageRequest);

        List<JobResponse> jobResponses = jobPage.getContent().stream()
                .map(JobResponse::fromJob)
                .toList();

        return JobListResponse.builder()
                .jobs(jobResponses)
                .currentPage(pageNumber)
                .pageSize(pageSize)
                .totalJobs(jobPage.getTotalElements())
                .totalPages(jobPage.getTotalPages())
                .hasNextPage(jobPage.hasNext())
                .build();
    }

    /**
     * Deletes a job from the database and removes all associated files from disk.
     * Refuses deletion if the job is currently PROCESSING.
     *
     * @param userId The authenticated user's ID
     * @param jobId  The job to delete
     */
    @Transactional
    public void deleteJob(Long userId, Long jobId) {
        Job job = jobRepository.findByIdAndOwnerId(jobId, userId)
                .orElseThrow(() -> {
                    boolean jobExists = jobRepository.existsById(jobId);
                    return jobExists
                        ? new UnauthorizedAccessException(jobId)
                        : new JobNotFoundException(jobId);
                });

        if (job.getStatus() == JobStatus.PROCESSING) {
            throw new IllegalStateException(
                "Cannot delete job " + jobId + " because it is currently PROCESSING."
            );
        }

        // Clean up all associated files to free disk space
        if (job.getInputPath() != null) {
            fileStorageService.deleteFile(job.getInputPath());
        }
        if (job.getOutputPath() != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode paths = new com.fasterxml.jackson.databind.ObjectMapper().readTree(job.getOutputPath());
                if (paths.has("thumbnail")) fileStorageService.deleteFile(paths.get("thumbnail").asText());
                if (paths.has("compressed")) fileStorageService.deleteFile(paths.get("compressed").asText());
            } catch (Exception e) {
                log.warn("Failed to parse output paths for deletion: {}", job.getOutputPath(), e);
            }
        }

        jobRepository.delete(job);
        log.info("Deleted job {} for user {}", jobId, userId);
    }
}
