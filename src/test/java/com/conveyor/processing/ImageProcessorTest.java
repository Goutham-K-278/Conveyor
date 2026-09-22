package com.conveyor.processing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the image processing pipeline.
 *
 * Uses @TempDir to create a real temp directory for each test — files are
 * actually written to disk and verified. This tests the real library behavior,
 * not just mock responses.
 *
 * The test image (test-image.jpg) is a small JPEG included in test resources.
 */
@DisplayName("Image Processing — Pipeline Tests")
class ImageProcessorTest {

    @TempDir
    Path temporaryDirectory;

    private ThumbnailGenerator thumbnailGenerator;
    private ImageCompressor imageCompressor;
    private MetadataExtractor metadataExtractor;

    private Path testImagePath;

    @BeforeEach
    void setupProcessorsAndTestImage(@TempDir Path thumbnailsDir, @TempDir Path compressedDir) throws IOException {
        thumbnailGenerator = new ThumbnailGenerator();
        ReflectionTestUtils.setField(thumbnailGenerator, "thumbnailsDirectoryPath", thumbnailsDir.toString());
        ReflectionTestUtils.setField(thumbnailGenerator, "thumbnailWidthPixels", 200);
        ReflectionTestUtils.setField(thumbnailGenerator, "thumbnailHeightPixels", 200);

        imageCompressor = new ImageCompressor();
        ReflectionTestUtils.setField(imageCompressor, "compressedDirectoryPath", compressedDir.toString());

        metadataExtractor = new MetadataExtractor();

        // Copy a small test image from test resources to the temp directory
        testImagePath = temporaryDirectory.resolve("test-image.jpg");
        try (InputStream testImageStream = getClass().getResourceAsStream("/test-images/sample.jpg")) {
            if (testImageStream != null) {
                Files.copy(testImageStream, testImagePath);
            } else {
                // Fallback: create a minimal JPEG-like file for testing
                Files.write(testImagePath, createMinimalJpegBytes());
            }
        }
    }

    @Test
    @DisplayName("Thumbnail generator should create a file at the output path")
    void shouldCreateThumbnailFileOnDisk() {
        String thumbnailPath = thumbnailGenerator.generateThumbnail(
            testImagePath.toString(), "test-thumbnail.jpg"
        );

        assertThat(thumbnailPath).isNotBlank();
        assertThat(Path.of(thumbnailPath)).exists();
    }

    @Test
    @DisplayName("Thumbnail should be smaller in file size than the original")
    void shouldProduceSmallerFileThanOriginal() throws IOException {
        long originalFileSizeBytes = Files.size(testImagePath);

        String thumbnailPath = thumbnailGenerator.generateThumbnail(
            testImagePath.toString(), "test-thumbnail.jpg"
        );

        long thumbnailFileSizeBytes = Files.size(Path.of(thumbnailPath));
        assertThat(thumbnailFileSizeBytes).isLessThanOrEqualTo(originalFileSizeBytes);
    }

    @Test
    @DisplayName("Image compressor should create a file at the output path")
    void shouldCreateCompressedFileOnDisk() {
        String compressedPath = imageCompressor.compressImage(
            testImagePath.toString(), "test-compressed.jpg"
        );

        assertThat(compressedPath).isNotBlank();
        assertThat(Path.of(compressedPath)).exists();
    }

    @Test
    @DisplayName("Metadata extractor should return at least file size and format")
    void shouldExtractBasicMetadataFields() {
        Map<String, Object> metadata = metadataExtractor.extractMetadata(
            testImagePath.toString(), "test-image.jpg"
        );

        assertThat(metadata).containsKey("fileSizeBytes");
        assertThat(metadata).containsKey("format");
        assertThat(metadata.get("format")).isEqualTo("JPG");
    }

    @Test
    @DisplayName("Metadata extractor should handle non-EXIF images gracefully")
    void shouldHandleImagesWithoutExifDataGracefully() {
        // Should not throw — EXIF extraction is best-effort
        Map<String, Object> metadata = metadataExtractor.extractMetadata(
            testImagePath.toString(), "screenshot.png"
        );

        assertThat(metadata).isNotEmpty();
    }

    // ── Test Helper ───────────────────────────────────────────────────────────

    /**
     * Creates a minimal valid JPEG header for tests when no sample image is available.
     * This is the absolute minimum bytes to create a parseable JPEG structure.
     */
    private byte[] createMinimalJpegBytes() {
        return new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xD9
        };
    }
}
