package com.conveyor.exception;

/**
 * Thrown when a user tries to access a job that belongs to someone else.
 * The GlobalExceptionHandler maps this to an HTTP 403 Forbidden response.
 *
 * We deliberately return 403 (not 404) to avoid leaking information
 * about whether the job ID exists at all.
 */
public class UnauthorizedAccessException extends RuntimeException {

    public UnauthorizedAccessException(Long jobId) {
        super("You do not have permission to access job: " + jobId);
    }
}
