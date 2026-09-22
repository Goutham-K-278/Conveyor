package com.conveyor.config;

import com.conveyor.exception.FileStorageException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads file storage paths from application.yml and ensures all required
 * directories exist when the application starts.
 *
 * This runs before any file uploads are accepted, so the API never tries
 * to write to a directory that doesn't exist.
 */
@Configuration
@Getter
public class StorageConfig {

    @Value("${storage.originals-directory}")
    private String originalsDirectoryPath;

    @Value("${storage.thumbnails-directory}")
    private String thumbnailsDirectoryPath;

    @Value("${storage.compressed-directory}")
    private String compressedDirectoryPath;

    /**
     * Creates all storage directories on startup if they don't already exist.
     * This is safe to call multiple times — createDirectories() is idempotent.
     */
    @PostConstruct
    public void createStorageDirectoriesOnStartup() {
        createDirectoryIfMissing(originalsDirectoryPath);
        createDirectoryIfMissing(thumbnailsDirectoryPath);
        createDirectoryIfMissing(compressedDirectoryPath);
    }

    private void createDirectoryIfMissing(String directoryPath) {
        try {
            Path directory = Paths.get(directoryPath);
            Files.createDirectories(directory);
        } catch (IOException cause) {
            throw new FileStorageException(
                "Failed to create storage directory: " + directoryPath, cause
            );
        }
    }
}
