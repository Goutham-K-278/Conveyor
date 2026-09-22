package com.conveyor.exception;

/**
 * Thrown when a file operation fails (saving, reading, or deleting).
 * The GlobalExceptionHandler maps this to an HTTP 500 Internal Server Error.
 *
 * We use a RuntimeException so callers don't need to declare it in
 * their method signatures — it propagates up naturally.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
