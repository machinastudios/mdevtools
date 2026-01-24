package com.machina.mdevtools.tasks.modreload;

import java.lang.reflect.Field;
import java.nio.file.Path;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;

public class ConfigFilesReload {
    /**
     * The logger for the config files reload task
     */
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ServerConfigReload");

    /**
     * Check if a file is a server config file.
     * @param path The path to check
     * @return True if the file is a server config file, false otherwise
     */
    public static boolean maybeReloadServerConfig(Path path) {
        // If it's the server config.json file
        if (path.equals(HytaleServerConfig.PATH.toAbsolutePath())) {
            return reloadServerConfig();
        }

        return false;
    }

    /**
     * Reload the server config
     * @return True if the server config was reloaded, false otherwise
     */
    private static boolean reloadServerConfig() {
        logger.debug("Reloading server config");

        try {
            Field configField = HytaleServer.class.getDeclaredField("hytaleServerConfig");
            configField.setAccessible(true);

            // Load the server config
            HytaleServerConfig config = HytaleServerConfig.load();

            // Replace the server config
            configField.set(HytaleServer.get(), config);

            logger.info("Reloaded server config");

            return true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.error("Failed to reload server config", e);
            return false;
        }
    }
}
