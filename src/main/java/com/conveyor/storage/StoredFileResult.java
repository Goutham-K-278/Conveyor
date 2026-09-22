package com.conveyor.storage;

import lombok.Builder;
import lombok.Getter;

/**
 * Returned by FileStorageService after successfully saving an uploaded file.
 *
 * The filePath is stored in the database (input_path column) so workers
 * and the API can locate the file later.
 */
@Getter
@Builder
public class StoredFileResult {

    /**
     * The full relative path to the stored file — e.g.:
     * "uploads/originals/a1b2c3d4-image.jpg"
     *
     * This is what gets saved to the jobs.input_path column.
     */
    private final String filePath;

    /**
     * Just the filename portion — e.g.:
     * "a1b2c3d4-image.jpg"
     *
     * Used to construct output paths for the thumbnail and compressed version.
     */
    private final String fileName;
}
