package com.machina.mdevtools.tasks.modreload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.machina.shared.factory.ModLogger;

/**
 * Utility methods for path handling and file checks used by the mod reload task.
 */
public final class ModReloadPathUtils {
    private ModReloadPathUtils() {
    }

    /**
     * Normalize a path for consistent comparison.
     * @param path The path to normalize
     * @return The normalized absolute path
     */
    public static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Check if a file is a mod file (.zip or .jar).
     * @param path The path to check
     * @return True if the file is a mod, false otherwise
     */
    public static boolean isModFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".zip") || fileName.endsWith(".jar");
    }

    /**
     * Check if a file is stable (size hasn't changed in the configured time).
     * @param filePath The path to the file
     * @param fileStabilityCheckMs Time in milliseconds to wait before checking size again
     * @param logger Logger used for debug/warn messages
     * @return True if the file is stable, false otherwise
     */
    public static boolean isFileStable(Path filePath, long fileStabilityCheckMs, ModLogger logger) {
        if (!Files.exists(filePath)) {
            return false;
        }

        try {
            // Get initial file size
            long initialSize = Files.size(filePath);

            // Wait for the stability check duration
            Thread.sleep(fileStabilityCheckMs);

            // Check if file size changed
            if (!Files.exists(filePath)) {
                return false;
            }

            long finalSize = Files.size(filePath);
            boolean isStable = initialSize == finalSize;

            if (!isStable) {
                logger.debug(
                    "File %s size changed: %d -> %d bytes",
                    filePath.getFileName().toString(),
                    initialSize,
                    finalSize
                );
            }

            return isStable;
        } catch (IOException e) {
            logger.warn("Error checking file stability for %s: %t", filePath.getFileName().toString(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

