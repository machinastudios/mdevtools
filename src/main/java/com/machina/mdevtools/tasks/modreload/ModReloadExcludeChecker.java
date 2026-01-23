package com.machina.mdevtools.tasks.modreload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.machina.mdevtools.Main;

/**
 * Utility class for checking if mods should be excluded from reloading.
 */
public final class ModReloadExcludeChecker {
    /**
     * Cache of compiled exclude patterns to avoid recompiling regex patterns
     */
    private final Map<String, Pattern> excludePatternCache = new HashMap<>();

    /**
     * Check if a mod should be excluded from reloading
     * @param modId The mod identifier
     * @return True if the mod should be excluded, false otherwise
     */
    public boolean shouldExcludeMod(PluginIdentifier modId) {
        // Ignore if the mod ID is null
        if (modId == null) {
            return false;
        }

        return shouldExcludeMod(modId.toString());
    }

    /**
     * Check if a mod should be excluded from reloading
     * @param modId The mod identifier or filename
     * @return True if the mod should be excluded, false otherwise
     */
    public boolean shouldExcludeMod(String modId) {
        // Get the exclude list
        List<String> excludeList = Main.INSTANCE.config.getStringList("mods.reload.exclude", List.of());

        // Iterate over the exclude list
        for (String exclude : excludeList) {
            // Check cache first
            Pattern pattern = excludePatternCache.get(exclude);
            
            // If not in cache, compile and cache it
            if (pattern == null) {
                pattern = Pattern.compile(
                    exclude
                        // Convert wildcards to regex patterns
                        .replace("*", ".*")

                        // Escape special regex characters
                        .replace("?", ".")
                        .replace(".", "\\.")
                        .replace("+", "\\+")
                        .replace("|", "\\|")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("[", "\\[")
                        .replace("]", "\\]")
                );
                excludePatternCache.put(exclude, pattern);
            }

            // Check if the mod ID matches the exclude
            if (pattern.matcher(modId).matches()) {
                return true;
            }
        }

        return false;
    }
}
