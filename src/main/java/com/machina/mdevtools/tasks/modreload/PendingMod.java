package com.machina.mdevtools.tasks.modreload;

import java.nio.file.Path;

/**
 * Record representing a pending mod that is waiting to be reloaded
 * @param path The path to the mod file
 * @param detectedAt Timestamp when the file change was first detected
 * @param retryCount Number of retry attempts made for this mod (0 for first attempt)
 */
public record PendingMod(Path path, long detectedAt, int retryCount, boolean isDeleted) {
    /**
     * Create a new PendingMod with retry count initialized to 0
     * @param path The path to the mod file
     * @param detectedAt Timestamp when the file change was first detected
     * @param retryCount Number of retry attempts made for this mod (0 for first attempt)
     * @param isDeleted Whether the mod is deleted
     */
    public PendingMod(Path path, long detectedAt, int retryCount, boolean isDeleted) {
        this.path = path;
        this.detectedAt = detectedAt;
        this.retryCount = retryCount;
        this.isDeleted = isDeleted;
    }
}