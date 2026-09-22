package com.conveyor.job;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The response body returned to clients for a single job.
 *
 * We never return the raw Job entity from the API — it would expose
 * internal fields like the worker ID and database relationships.
 * This DTO gives clients exactly what they need, nothing more.
 */
@Getter
@Builder
public class JobResponse {

    /** The unique job ID — clients use this for subsequent status checks */
    private final Long jobId;

    /** Current lifecycle status: PENDING, PROCESSING, COMPLETED, or FAILED */
    private final String status;

    /** What kind of job this is — always IMAGE_PROCESS for now */
    private final String type;

    /**
     * JSON string with output file paths — only present when status is COMPLETED.
     * Format: {"thumbnail":"uploads/thumbnails/x.jpg","compressed":"uploads/compressed/x.jpg"}
     * Null when the job is still PENDING or PROCESSING.
     */
    private final String outputPath;

    /**
     * Extracted metadata (width, height, format, EXIF) — only present when COMPLETED.
     * Null when the job is still in progress.
     */
    private final Map<String, Object> metadata;

    /** Human-readable error description — only present when status is FAILED */
    private final String errorReason;

    /** How many times processing has been attempted */
    private final int attempts;

    /** When this job was created */
    private final LocalDateTime createdAt;

    /** When this job was last updated */
    private final LocalDateTime updatedAt;

    // ── Factory Method ────────────────────────────────────────────────────────

    /**
     * Converts a Job entity into a JobResponse DTO.
     * Controllers always call this rather than building the DTO manually.
     */
    public static JobResponse fromJob(Job job) {
        return JobResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus().name())
                .type(job.getType().name())
                .outputPath(job.getOutputPath())
                .metadata(job.getMetadata())
                .errorReason(job.getErrorReason())
                .attempts(job.getAttempts())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
