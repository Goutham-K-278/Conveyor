package com.conveyor.job;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Wraps a paginated list of jobs for the GET /jobs endpoint.
 *
 * Includes pagination metadata so clients can implement "load more" UI
 * without fetching all jobs at once.
 */
@Getter
@Builder
public class JobListResponse {

    /** The jobs on the current page */
    private final List<JobResponse> jobs;

    /** Current page number (0-indexed) */
    private final int currentPage;

    /** Number of items per page */
    private final int pageSize;

    /** Total number of jobs this user has (across all pages) */
    private final long totalJobs;

    /** Total number of pages */
    private final int totalPages;

    /** Whether there is a next page available */
    private final boolean hasNextPage;
}
