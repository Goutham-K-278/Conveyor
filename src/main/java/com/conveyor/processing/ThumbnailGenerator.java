package com.conveyor.processing;

import com.conveyor.exception.FileStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Generates a fixed-size thumbnail from an original image.
 *
 * Uses Thumbnailator — a Java library that handles resizing, rotation,
 * aspect ratio correction, and format conversion with a clean builder API.
 *
 * The thumbnail is always written to the thumbnails directory with the
 * same filename as the original, so it's easy to locate by job ID.
 *
 * Output quality is set to 0.85 (85%) — high enough for previews,
 * small enough to be a fast-loading thumbnail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerator {

    @Value("${storage.thumbnails-directory}")
    private String thumbnailsDirectoryPath;

    @Value("${worker.thumbnail-width-pixels}")
    private int thumbnailWidthPixels;

    @Value("${worker.thumbnail-height-pixels}")
    private int thumbnailHeightPixels;

    // Quality factor: 0.0 (worst) to 1.0 (lossless) — 0.85 balances size vs. quality
    private static final double THUMBNAIL_QUALITY = 0.85;

    /**
     * Resizes the input image to the configured thumbnail dimensions.
     *
     * Thumbnailator preserves aspect ratio by default when using size(),
     * and handles JPEG, PNG, GIF, and BMP formats automatically.
     *
     * @param originalImagePath Full path to the source image on disk
     * @param outputFileName    The filename to use for the thumbnail output
     * @return Full path to the saved thumbnail file
     */
    public String generateThumbnail(String originalImagePath, String outputFileName) {
        File originalFile = Paths.get(originalImagePath).toFile();
        String thumbnailFilePath = thumbnailsDirectoryPath + "/" + outputFileName;
        File thumbnailOutputFile = new File(thumbnailFilePath);

        try {
            Thumbnails.of(originalFile)
                    .size(thumbnailWidthPixels, thumbnailHeightPixels)
                    .outputQuality(THUMBNAIL_QUALITY)
                    .toFile(thumbnailOutputFile);

            log.info("Generated thumbnail: {} ({}x{} px)", thumbnailFilePath, thumbnailWidthPixels, thumbnailHeightPixels);
            return thumbnailFilePath;

        } catch (IOException ioError) {
            throw new FileStorageException(
                "Failed to generate thumbnail for: " + originalImagePath, ioError
            );
        }
    }
}
