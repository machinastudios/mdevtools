package com.machina.mdevtools.tasks.modreload;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.machina.shared.factory.ModLogger;

/**
 * Utility class for plugin management operations during mod reload.
 */
public final class ModReloadPluginManager {
    private final ModLogger logger;

    public ModReloadPluginManager(ModLogger logger) {
        this.logger = logger;
    }

    /**
     * Get the plugin map using reflection
     * @return The plugin map
     * @throws Exception if reflection fails
     */
    public Map<PluginIdentifier, JavaPlugin> getPluginMap() throws Exception {
        Field pluginsField = PluginManager.class.getDeclaredField("plugins");
        pluginsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<PluginIdentifier, JavaPlugin> pluginMap = (Map<PluginIdentifier, JavaPlugin>) pluginsField.get(PluginManager.get());
        return pluginMap;
    }

    /**
     * Find plugin identifier by file path by checking all loaded plugins
     * @param filePath The file path to search for
     * @param hytalePluginList The plugin map
     * @return The plugin identifier if found, null otherwise
     */
    public PluginIdentifier findPluginIdByPath(Path filePath, Map<PluginIdentifier, JavaPlugin> hytalePluginList) {
        Path normalizedTargetPath = ModReloadPathUtils.normalizePath(filePath);

        // Iterate over the plugin map
        for (Map.Entry<PluginIdentifier, JavaPlugin> entry : hytalePluginList.entrySet()) {
            try {
                // Get the plugin
                JavaPlugin plugin = entry.getValue();
                if (plugin == null) {
                    continue;
                }

                Path pluginPath = plugin.getFile();
                if (pluginPath == null) {
                    continue;
                }

                if (ModReloadPathUtils.normalizePath(pluginPath).equals(normalizedTargetPath)) {
                    return entry.getKey();
                }
            } catch (Exception e) {
                logger.debug("Error checking plugin path for %s: %t", entry.getKey(), e);
            }
        }
        return null;
    }

    /**
     * Load a plugin
     * @param pluginId The plugin identifier
     * @param pluginName The plugin name for logging
     * @return True if successful, false otherwise
     */
    public boolean loadPlugin(PluginIdentifier pluginId, String pluginName) {
        logger.info("Mod %s is not loaded yet, will be loaded", pluginName);

        // Load the plugin
        if (PluginManager.get().load(pluginId)) {
            logger.info("Mod %s has been loaded", pluginName);
            return true;
        } else {
            logger.error("Failed to load mod %s", pluginName);
            return false;
        }
    }

    /**
     * Reload a plugin
     * @param pluginId The plugin identifier
     * @param pluginName The plugin name for logging
     * @return True if successful, false otherwise
     */
    public boolean reloadPlugin(PluginIdentifier pluginId, String pluginName) {
        // Get the asset module
        var assetModule = AssetModule.get();
        var assetPacketExists = assetModule.getAssetPack(pluginId.toString()) != null;

        // If an asset packet for the plugin is already registered, unregister it
        // Idk why Hytale isn't doing this automatically
        if (assetPacketExists) {
            logger.info("Unregistering asset packet for mod %s", pluginName);
            logger.info("This will cause some erros in the console, please ignore them, they're harmless");

            // This will cause some erros in the console, but it's necessary
            assetModule.unregisterPack(pluginId.toString());
        }

        // Reload the plugin
        if (!PluginManager.get().reload(pluginId)) {
            logger.error("Failed to reload mod %s", pluginName);
            return false;
        }

        // Reload the asset packet
        ModReloadAssetReload.reloadAssetPacket(pluginId);
        
        logger.info("Mod %s has been reloaded", pluginName);
        return true;
    }
}
