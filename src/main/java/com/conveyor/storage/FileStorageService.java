package com.conveyor.storage;

import com.conveyor.config.StorageConfig;
import com.conveyor.exception.FileStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Handles all file I/O for Conveyor — saving uploaded originals,
 * loading files for processing, and deleting them when needed.
 *
 * Files are stored on the local filesystem under the paths configured in
 * application.yml. In a production system, this would be swapped for
 * S3 or another object store, but the interface stays the same.
 *
 * Naming convention: every file gets a UUID prefix to prevent collisions
 * when two users upload files with the same name (e.g. "photo.jpg").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StorageConfig storageConfig;

    /**
     * Saves an uploaded multipart file to the originals directory.
     *
     * The file is stored as: "{uuid}-{original-filename}"
     * This guarantees uniqueness while keeping the original name readable.
     *
     * @param uploadedFile The file uploaded via the REST API
     * @return A StoredFileResult with the path and filename for database storage
     */
    public StoredFileResult storeOriginalFile(MultipartFile uploadedFile) {
        String cleanOriginalFileName = cleanAndValidateFileName(uploadedFile);
        String uniqueFileName = UUID.randomUUID().toString().substring(0, 8) + "-" + cleanOriginalFileName;

        Path destinationPath = Paths.get(storageConfig.getOriginalsDirectoryPath())
                                    .resolve(uniqueFileName)
                                    .toAbsolutePath()
                                    .normalize();

        copyFileToDestination(uploadedFile, destinationPath);

        log.info("Stored original file: {}", destinationPath);
        return StoredFileResult.builder()
                .filePath(storageConfig.getOriginalsDirectoryPath() + "/" + uniqueFileName)
                .fileName(uniqueFileName)
                .build();
    }

    /**
     * Resolves a stored file path to a Java Path object.
     * Used by the worker to open and process the original image.
     *
     * @param storedFilePath The path as saved in the database (e.g. "uploads/originals/abc.jpg")
     * @return A Path that can be opened by ImageIO or Thumbnailator
     */
    public Path resolveFilePath(String storedFilePath) {
        return Paths.get(storedFilePath).toAbsolutePath().normalize();
    }

    /**
     * Deletes a file from disk.
     * Used during job cancellation to free up storage space.
     */
    public void deleteFile(String storedFilePath) {
        try {
            Path fileToDelete = resolveFilePath(storedFilePath);
            boolean wasDeleted = Files.deleteIfExists(fileToDelete);
            if (wasDeleted) {
                log.info("Deleted file: {}", storedFilePath);
            } else {
                log.warn("Tried to delete file that didn't exist: {}", storedFilePath);
            }
        } catch (IOException cause) {
            log.error("Failed to delete file: {}", storedFilePath, cause);
            // We intentionally don't throw here — a failed delete shouldn't
            // block a job cancellation from succeeding in the database.
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private String cleanAndValidateFileName(MultipartFile uploadedFile) {
        if (uploadedFile.isEmpty()) {
            throw new FileStorageException("Cannot store an empty file.");
        }

        // StringUtils.cleanPath prevents directory traversal attacks like "../../../etc/passwd"
        String cleanFileName = StringUtils.cleanPath(
            uploadedFile.getOriginalFilename() != null
                ? uploadedFile.getOriginalFilename()
                : "unknown-file"
        );

        if (cleanFileName.contains("..")) {
            throw new FileStorageException(
                "Filename contains illegal characters: " + cleanFileName
            );
        }

        return cleanFileName;
    }

    private void copyFileToDestination(MultipartFile uploadedFile, Path destinationPath) {
        try {
            Files.copy(uploadedFile.getInputStream(), destinationPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cause) {
            throw new FileStorageException(
                "Failed to save uploaded file to: " + destinationPath, cause
            );
        }
    }
}
