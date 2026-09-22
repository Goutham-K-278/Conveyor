package com.conveyor.job;

import com.conveyor.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The central domain model of Conveyor — represents a single background task.
 *
 * A job's entire lifecycle is tracked here:
 *   Created by the API → queued in Redis → claimed by a worker
 *   → processed → COMPLETED (or FAILED after MAX_ATTEMPTS retries)
 *
 * The columns that enable fault tolerance:
 *   - attempts:   how many times this job has been tried
 *   - claimed_by: which worker instance owns it right now
 *   - claimed_at: when it was claimed (used to detect if a worker crashed)
 *   - error_reason: human-readable description of what went wrong
 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    // ── Identity ──────────────────────────────────────────────────────────────
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Ownership ─────────────────────────────────────────────────────────────
    /** The user who submitted this job — they are the only one who can query it */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    // ── Job Classification ────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    // ── Lifecycle Status ──────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    // ── File Paths ────────────────────────────────────────────────────────────
    /** Where the original uploaded file is stored on disk */
    @Column(name = "input_path", nullable = false)
    private String inputPath;

    /**
     * Where the processed outputs are stored — this is a JSON string like:
     * {"thumbnail": "uploads/thumbnails/abc.jpg", "compressed": "uploads/compressed/abc.jpg"}
     * Null until the job reaches COMPLETED status.
     */
    @Column(name = "output_path")
    private String outputPath;

    // ── Processing Results ────────────────────────────────────────────────────
    /**
     * Flexible metadata storage as JSONB — contains:
     * width, height, format, fileSize, and any available EXIF fields.
     * Null until the worker finishes extraction.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    // ── Fault Tolerance Tracking ──────────────────────────────────────────────
    /** How many times a worker has tried (and failed) to process this job */
    @Column(nullable = false)
    private int attempts;

    /** Set when the job permanently fails — explains what went wrong */
    @Column(name = "error_reason")
    private String errorReason;

    // ── Worker Ownership ─────────────────────────────────────────────────────
    /**
     * A unique identifier for the worker that currently owns this job
     * (e.g. "worker-hostname-abc123"). Used for debugging and to correlate
     * which worker needs to be checked if a job gets stuck.
     */
    @Column(name = "claimed_by")
    private String claimedByWorkerId;

    /**
     * The moment a worker claimed this job.
     * The stuck-job reaper queries for jobs where:
     *   status = PROCESSING AND claimed_at < (now - stuck_timeout)
     * to find crashed workers.
     */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    // ── Timestamps ────────────────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── JPA Lifecycle Hooks ───────────────────────────────────────────────────
    @PrePersist
    private void setTimestampsBeforeFirstInsert() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    private void updateTimestampBeforeEachUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Factory method for creating a brand-new job right after an API upload.
     * Sets the initial state to PENDING with zero attempts.
     */
    public static Job createNewJob(User owner, JobType type, String inputPath) {
        Job newJob = new Job();
        newJob.setOwner(owner);
        newJob.setType(type);
        newJob.setInputPath(inputPath);
        newJob.setStatus(JobStatus.PENDING);
        newJob.setAttempts(0);
        return newJob;
    }
}
