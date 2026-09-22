package com.conveyor.processing;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.file.FileSystemDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts metadata from an image file.
 *
 * We combine two approaches:
 *
 *   1. Java ImageIO (javax.imageio) — reads pixel data to get width and height
 *      reliably across all image formats (JPEG, PNG, BMP, GIF, TIFF).
 *
 *   2. metadata-extractor library (com.drewnoakes) — reads the embedded EXIF
 *      metadata from the file headers without decoding the full pixel data.
 *      EXIF data includes: camera model, aperture, GPS coordinates, date taken, etc.
 *
 * Results are returned as a Map<String, Object> which gets stored as JSONB
 * in the database, giving us flexible querying without a fixed schema.
 *
 * EXIF extraction is best-effort — many images (screenshots, web images)
 * don't have EXIF data, so we gracefully skip it rather than failing.
 */
@Slf4j
@Component
public class MetadataExtractor {

    /**
     * Reads all available metadata from an image file.
     *
     * @param imageFilePath Full path to the image on disk
     * @param originalFileName The original upload filename (for format detection)
     * @return A map of metadata key-value pairs for JSONB storage
     */
    public Map<String, Object> extractMetadata(String imageFilePath, String originalFileName) {
        Map<String, Object> allMetadata = new LinkedHashMap<>();

        // ── Basic file metadata ───────────────────────────────────────────────
        try {
            File imageFile = Paths.get(imageFilePath).toFile();
            long fileSizeBytes = Files.size(imageFile.toPath());
            allMetadata.put("fileSizeBytes", fileSizeBytes);
            allMetadata.put("originalFileName", originalFileName);
        } catch (IOException sizeReadError) {
            log.warn("Could not read file size for: {}", imageFilePath);
        }

        // ── Pixel dimensions (width × height) ────────────────────────────────
        // We read this via ImageIO which actually decodes the image header
        try {
            BufferedImage decodedImage = ImageIO.read(new File(imageFilePath));
            if (decodedImage != null) {
                allMetadata.put("widthPixels", decodedImage.getWidth());
                allMetadata.put("heightPixels", decodedImage.getHeight());
            }
        } catch (IOException dimensionReadError) {
            log.warn("Could not read image dimensions for: {}", imageFilePath, dimensionReadError);
        }

        // ── Image format detection ────────────────────────────────────────────
        String fileExtension = extractFileExtension(originalFileName);
        allMetadata.put("format", fileExtension.toUpperCase());

        // ── EXIF Data (best-effort — many images won't have this) ────────────
        extractExifDataIfAvailable(imageFilePath, allMetadata);

        log.info("Extracted {} metadata fields from: {}", allMetadata.size(), imageFilePath);
        return allMetadata;
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void extractExifDataIfAvailable(String imageFilePath, Map<String, Object> metadataOutput) {
        try {
            Metadata exifContainer = ImageMetadataReader.readMetadata(new File(imageFilePath));

            // Camera information from the main EXIF directory
            ExifIFD0Directory mainExifDirectory = exifContainer.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (mainExifDirectory != null) {
                safelyAddExifField(metadataOutput, "cameraMake", mainExifDirectory, ExifIFD0Directory.TAG_MAKE);
                safelyAddExifField(metadataOutput, "cameraModel", mainExifDirectory, ExifIFD0Directory.TAG_MODEL);
            }

            // Detailed camera settings from the sub-EXIF directory
            ExifSubIFDDirectory subExifDirectory = exifContainer.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subExifDirectory != null) {
                safelyAddExifField(metadataOutput, "dateTimeTaken", subExifDirectory, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                safelyAddExifField(metadataOutput, "shutterSpeed", subExifDirectory, ExifSubIFDDirectory.TAG_SHUTTER_SPEED);
                safelyAddExifField(metadataOutput, "aperture", subExifDirectory, ExifSubIFDDirectory.TAG_APERTURE);
                safelyAddExifField(metadataOutput, "isoSpeed", subExifDirectory, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT);
            }

        } catch (Exception exifReadError) {
            // EXIF data is optional — log a debug message and move on
            log.debug("No EXIF data found in: {} (this is normal for PNGs, screenshots, etc.)", imageFilePath);
        }
    }

    private void safelyAddExifField(Map<String, Object> output, String fieldName, com.drew.metadata.Directory directory, int tagType) {
        try {
            String tagValue = directory.getString(tagType);
            if (tagValue != null && !tagValue.isBlank()) {
                output.put(fieldName, tagValue);
            }
        } catch (Exception tagReadError) {
            // Skip this field — partial EXIF is fine
        }
    }

    private String extractFileExtension(String fileName) {
        int lastDotPosition = fileName.lastIndexOf('.');
        if (lastDotPosition == -1 || lastDotPosition == fileName.length() - 1) {
            return "UNKNOWN";
        }
        return fileName.substring(lastDotPosition + 1);
    }
}
