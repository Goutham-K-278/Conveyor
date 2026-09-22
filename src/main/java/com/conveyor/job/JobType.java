package com.conveyor.job;

/**
 * Describes what kind of processing a job requires.
 *
 * Currently only IMAGE_PROCESS exists, but this enum makes it easy
 * to add VIDEO_TRANSCODE, PDF_GENERATE, BULK_EMAIL, etc. in the future
 * without changing the database schema.
 */
public enum JobType {

    /**
     * An image processing job: generates a thumbnail, compresses the
     * original, and extracts metadata (width, height, format, EXIF data).
     */
    IMAGE_PROCESS
}
