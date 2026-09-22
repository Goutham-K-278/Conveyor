package com.conveyor.processing;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Contains all outputs from a successful image processing run.
 *
 * After the ImageProcessor finishes, it wraps everything into this object.
 * The WorkerLoop then reads from this to update the job record in the database.
 */
@Getter
@Builder
public class ImageProcessingResult {

    /** Path to the generated thumbnail (e.g. "uploads/thumbnails/abc-image.jpg") */
    private final String thumbnailPath;

    /** Path to the compressed version of the original (e.g. "uploads/compressed/abc-image.jpg") */
    private final String compressedPath;

    /**
     * Extracted metadata: width, height, format, file size in bytes,
     * and any available EXIF fields (camera model, GPS coords, etc.)
     */
    private final Map<String, Object> extractedMetadata;

    /**
     * Converts the output paths to a simple JSON string for storage in the
     * jobs.output_path column (TEXT column, not JSONB).
     *
     * Format: {"thumbnail":"uploads/thumbnails/x.jpg","compressed":"uploads/compressed/x.jpg"}
     */
    public String getOutputPathsAsJson() {
        return String.format(
            "{\"thumbnail\":\"%s\",\"compressed\":\"%s\"}",
            thumbnailPath != null ? thumbnailPath.replace("\\", "/") : "",
            compressedPath != null ? compressedPath.replace("\\", "/") : ""
        );
    }
}
