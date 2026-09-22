package com.conveyor.processing;

import com.conveyor.job.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Orchestrates the complete image processing pipeline for a single job.
 *
 * This is the top-level coordinator — it does not do any processing itself.
 * Instead, it delegates to three focused components in sequence:
 *
 *   1. ThumbnailGenerator → creates a small preview image
 *   2. ImageCompressor    → creates a smaller-file-size version of the original
 *   3. MetadataExtractor  → reads technical metadata (dimensions, EXIF, etc.)
 *
 * The WorkerLoop calls this single method and gets back an ImageProcessingResult
 * containing all three outputs, ready to be saved to the database.
 *
 * If any step throws an exception, it propagates up to the WorkerLoop,
 * which handles retry logic and failure recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessor {

    private final ThumbnailGenerator thumbnailGenerator;
    private final ImageCompressor imageCompressor;
    private final MetadataExtractor metadataExtractor;

    /**
     * Runs the full image processing pipeline for a job.
     *
     * @param job The job containing the inputPath to the uploaded original image
     * @return An ImageProcessingResult with paths to all outputs and extracted metadata
     */
    public ImageProcessingResult processImageJob(Job job) {
        String originalImagePath = job.getInputPath();
        String outputFileName = extractOutputFileName(originalImagePath);

        log.info("Starting image processing pipeline for job {} — input: {}", job.getId(), originalImagePath);

        // ── Step 1: Generate Thumbnail ────────────────────────────────────────
        String thumbnailPath = thumbnailGenerator.generateThumbnail(originalImagePath, outputFileName);

        // ── Step 2: Compress the Original ────────────────────────────────────
        String compressedPath = imageCompressor.compressImage(originalImagePath, outputFileName);

        // ── Step 3: Extract Metadata ──────────────────────────────────────────
        Map<String, Object> extractedMetadata = metadataExtractor.extractMetadata(
            originalImagePath, outputFileName
        );

        log.info("Image processing pipeline completed for job {}", job.getId());

        return ImageProcessingResult.builder()
                .thumbnailPath(thumbnailPath)
                .compressedPath(compressedPath)
                .extractedMetadata(extractedMetadata)
                .build();
    }

    // ── Private Helper ────────────────────────────────────────────────────────

    /**
     * Extracts just the filename from a full stored path.
     * Example: "uploads/originals/a1b2-image.jpg" → "a1b2-image.jpg"
     */
    private String extractOutputFileName(String fullStoredPath) {
        int lastSlashPosition = fullStoredPath.lastIndexOf('/');
        if (lastSlashPosition == -1) {
            return fullStoredPath;
        }
        return fullStoredPath.substring(lastSlashPosition + 1);
    }
}
