package com.conveyor.exception;

/**
 * Thrown when a client requests a job ID that doesn't exist.
 * The GlobalExceptionHandler maps this to an HTTP 404 Not Found response.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(Long jobId) {
        super("No job found with ID: " + jobId);
    }
}
