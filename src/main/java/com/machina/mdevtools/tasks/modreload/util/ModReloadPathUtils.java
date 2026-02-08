package com.machina.mdevtools.tasks.modreload.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.hypixel.hytale.server.core.Options;
import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;

/**
 * Utility methods for path handling and file checks used by the mod reload task.
 */
public final class ModReloadPathUtils {
    /**
     * The logger for the mod reload path utils
     */
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadPathUtils");

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
     * Get the paths to watch for mod reloads.
     * @return The paths to watch for mod reloads
     */
    public static List<Path> getModPaths() {
        List<Path> modsPath = List.of(
            // This path doesn't have an option for it, it's locked in
            Path.of("builtin")
        );

        // Workaround for java.lang.UnsupportedOperationException (ImmutableCollections)
        // List.of() returns an immutable list, so we must use a mutable list for addAll()
        List<Path> mutableModsPath = new ArrayList<>(modsPath);

        // Add the mods option
        mutableModsPath.addAll(Options.MODS_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the early plugin directories
        mutableModsPath.addAll(Options.EARLY_PLUGIN_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the configuration directories
        mutableModsPath.addAll(Main.INSTANCE.config.getList("mods.reload.additionalDirectories", List.of()).stream()
            .map(Object::toString)
            .map(Path::of)
            .toList());

        mutableModsPath = mutableModsPath.stream()
            // Deduplicate the list
            .distinct()
            // Resolve into absolute paths
            .map(p -> p.toAbsolutePath())
            // Normalize the path
            .map(p -> p.normalize())
            // Convert to a list
            .collect(Collectors.toList());

        // Final list must be modifiable
        return mutableModsPath;
    }

    /**
     * Check if a file is a mod file.
     * @param path The path to check
     * @return True if the file is a mod, false otherwise
     */
    public static boolean isModFile(Path path) {
        // If not in one of the mod paths, it's not a mod file
        boolean isModPath = false;

        // Check if the path is in one of the mod paths
        for (Path modPath : getModPaths()) {
            logger.debug("Checking if %s is in %s", path, modPath);

            // If the path starts with the mod path, it's a mod file
            if (path.startsWith(modPath)) {
                isModPath = true;
                break;
            }
        }

        // Get the file name
        String fileName = path.getFileName().toString();

        // Must end with .zip or .jar
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
