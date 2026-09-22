package com.conveyor.job;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * REST controller for all job-related HTTP endpoints.
 *
 * All endpoints require authentication — the JWT filter runs before this
 * controller and populates the SecurityContext with the authenticated user's ID.
 *
 * Base path: /jobs
 *
 * Endpoints:
 *   POST   /jobs/image     — upload an image, create a processing job
 *   GET    /jobs/{id}      — check the status and results of a specific job
 *   GET    /jobs           — list all jobs for the current user (paginated)
 *   DELETE /jobs/{id}      — cancel a pending job
 *
 * This controller is intentionally thin — no business logic lives here.
 * All logic is delegated to JobService.
 */
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /**
     * Accepts an image file upload and immediately returns a job ID.
     * Processing happens asynchronously in the background — the user
     * does NOT wait for the image to be resized here.
     *
     * Request:  POST /jobs/image
     *           Content-Type: multipart/form-data
     *           Body: file=<image file>
     *
     * Response: 201 Created
     *           { "jobId": 123, "status": "PENDING", ... }
     *
     * @param authenticatedUserId The user ID extracted from the valid JWT token
     * @param imageFile           The uploaded image file from the multipart form
     */
    @PostMapping("/image")
    public ResponseEntity<JobResponse> uploadImageAndCreateJob(
            @AuthenticationPrincipal Long authenticatedUserId,
            @RequestParam("file") MultipartFile imageFile
    ) {
        JobResponse createdJob = jobService.createImageJob(authenticatedUserId, imageFile);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdJob);
    }

    /**
     * Returns the current status and results of a single job.
     * Poll this endpoint periodically to check when processing completes.
     *
     * Request:  GET /jobs/{id}
     *           Authorization: Bearer <token>
     *
     * Response: 200 OK — with current status (and output paths if COMPLETED)
     *           404   — if the job ID doesn't exist
     *           403   — if the job belongs to a different user
     *
     * @param authenticatedUserId The user ID from the JWT (for ownership check)
     * @param jobId               The job ID from the URL path
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJobStatus(
            @AuthenticationPrincipal Long authenticatedUserId,
            @PathVariable Long jobId
    ) {
        JobResponse jobStatus = jobService.getJobStatus(authenticatedUserId, jobId);
        return ResponseEntity.ok(jobStatus);
    }

    /**
     * Serves the generated thumbnail image directly.
     * Requires authentication, which can be passed via the Authorization header
     * or a 'token' query parameter (for use in <img> tags).
     *
     * Request:  GET /jobs/{id}/thumbnail?token=<token>
     *
     * Response: 200 OK — with image bytes
     *           404   — if not found or not completed
     *           403   — if access denied
     */
    @GetMapping("/{jobId}/thumbnail")
    public ResponseEntity<Resource> getJobThumbnail(
            @AuthenticationPrincipal Long authenticatedUserId,
            @PathVariable Long jobId
    ) {
        JobResponse jobStatus = jobService.getJobStatus(authenticatedUserId, jobId);
        
        if (jobStatus == null || jobStatus.getOutputPath() == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jobStatus.getOutputPath());
            String thumbPath = root.path("thumbnail").asText(null);
            
            if (thumbPath == null) {
                return ResponseEntity.notFound().build();
            }

            Path file = Paths.get(thumbPath);
            Resource resource = new UrlResource(file.toUri());
            
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(file);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns all jobs belonging to the authenticated user, newest first.
     * Results are paginated — use 'page' and 'size' query params to navigate.
     *
     * Request:  GET /jobs?page=0&size=20
     *           Authorization: Bearer <token>
     *
     * Response: 200 OK
     *           { "jobs": [...], "totalJobs": 42, "totalPages": 3, ... }
     *
     * @param authenticatedUserId The user ID from the JWT
     * @param page                Page number, 0-indexed (default: 0)
     * @param size                Items per page (default: 20, max: 100)
     */
    @GetMapping
    public ResponseEntity<JobListResponse> listCurrentUserJobs(
            @AuthenticationPrincipal Long authenticatedUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Cap page size to prevent abuse (requesting 10,000 items at once)
        int cappedPageSize = Math.min(size, 100);
        JobListResponse userJobs = jobService.listUserJobs(authenticatedUserId, page, cappedPageSize);
        return ResponseEntity.ok(userJobs);
    }

    /**
     * Deletes a job — removes it from the database and deletes associated files.
     * Once a worker starts processing (PROCESSING status), deletion is refused.
     *
     * Request:  DELETE /jobs/{id}
     *           Authorization: Bearer <token>
     *
     * Response: 204 No Content — job deleted successfully
     *           409 Conflict   — job is not in a deletable state
     *           403/404        — ownership or existence issues
     *
     * @param authenticatedUserId The user ID from the JWT
     * @param jobId               The job ID to delete
     */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> deleteJob(
            @AuthenticationPrincipal Long authenticatedUserId,
            @PathVariable Long jobId
    ) {
        jobService.deleteJob(authenticatedUserId, jobId);
        return ResponseEntity.noContent().build();
    }
}
