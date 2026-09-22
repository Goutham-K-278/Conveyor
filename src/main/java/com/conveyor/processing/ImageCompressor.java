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
 * Compresses an image to a smaller file size while maintaining acceptable quality.
 *
 * We reuse Thumbnailator here — it doesn't just resize, it also handles
 * quality-based JPEG compression. By passing the original dimensions and
 * reducing quality to 0.7 (70%), we get smaller file sizes without resizing.
 *
 * Typical results:
 *   - A 5MB JPEG at full resolution → ~1-2MB at 70% quality
 *   - Visually indistinguishable in most use cases
 *
 * Why we don't just use ImageIO directly: Thumbnailator handles edge cases
 * like CMYK color profiles, progressive JPEGs, and EXIF rotation automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCompressor {

    @Value("${storage.compressed-directory}")
    private String compressedDirectoryPath;

    // Quality factor for the compressed output — 70% is a good balance
    private static final double COMPRESSION_QUALITY = 0.70;

    /**
     * Compresses the input image and saves it to the compressed directory.
     *
     * We use Thumbnails.scale(1.0) to keep the original dimensions,
     * only reducing the quality/file-size via outputQuality().
     *
     * @param originalImagePath Full path to the source image on disk
     * @param outputFileName    The filename to use for the compressed output
     * @return Full path to the saved compressed file
     */
    public String compressImage(String originalImagePath, String outputFileName) {
        File originalFile = Paths.get(originalImagePath).toFile();
        String compressedFilePath = compressedDirectoryPath + "/" + outputFileName;
        File compressedOutputFile = new File(compressedFilePath);

        try {
            long originalFileSizeBytes = originalFile.length();

            // scale(1.0) = keep original dimensions, only reduce quality
            Thumbnails.of(originalFile)
                    .scale(1.0)
                    .outputQuality(COMPRESSION_QUALITY)
                    .toFile(compressedOutputFile);

            long compressedFileSizeBytes = compressedOutputFile.length();
            double compressionRatioPercent = (1.0 - (double) compressedFileSizeBytes / originalFileSizeBytes) * 100;

            log.info(
                "Compressed image: {} — original: {} bytes, compressed: {} bytes ({}% reduction)",
                compressedFilePath,
                originalFileSizeBytes,
                compressedFileSizeBytes,
                String.format("%.1f", compressionRatioPercent)
            );

            return compressedFilePath;

        } catch (IOException ioError) {
            throw new FileStorageException(
                "Failed to compress image: " + originalImagePath, ioError
            );
        }
    }
}
