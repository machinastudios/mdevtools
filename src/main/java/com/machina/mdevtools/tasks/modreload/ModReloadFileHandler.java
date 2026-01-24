package com.machina.mdevtools.tasks.modreload;

import java.nio.file.Path;
import java.util.Map;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.util.HybridWatcher;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.ModJarUtils;
import com.machina.shared.util.PlayerUtil;

/**
 * Handler for mod file update and deletion operations.
 */
public final class ModReloadFileHandler {
    /**
     * List of mod identifiers that are being reloaded
     */
    private final Map<PluginIdentifier, ModReloadState> reloadingMods;

    private final ModLogger logger;
    private final ModReloadPluginManager pluginManager;
    private final ModReloadDependencyManager dependencyManager;
    private final ModReloadExcludeChecker excludeChecker;

    public ModReloadFileHandler(
        Map<PluginIdentifier, ModReloadState> reloadingMods,
        ModLogger logger,
        ModReloadPluginManager pluginManager,
        ModReloadDependencyManager dependencyManager,
        ModReloadExcludeChecker excludeChecker
    ) {
        this.reloadingMods = reloadingMods;
        this.logger = logger;
        this.pluginManager = pluginManager;
        this.dependencyManager = dependencyManager;
        this.excludeChecker = excludeChecker;
    }

    /**
     * Handle mod file update or deletion
     * @param filePath The path to the mod file
     * @param eventType The event type (MODIFIED, CREATED, or DELETED)
     * @return The result of the operation
     * @throws Exception if an error occurs during the operation
     */
    public ModReloadResult onModFileUpdated(Path filePath, HybridWatcher.EventType eventType) throws Exception {
        String fileName = filePath.getFileName().toString();

        // If the mod file is deleted
        if (eventType == HybridWatcher.EventType.DELETED) {
            return onModFileDeleted(filePath, fileName);
        }

        logger.info("Mod file %s updated", fileName);
        PluginIdentifier modId = null;

        try {
            // Get the mod manifest
            ModJarUtils.ModManifest manifest = ModJarUtils.getModManifest(filePath);

            // Ignore if the manifest is missing or invalid
            if (manifest == null) {
                String message = "manifest.json is missing or invalid, may be incomplete";
                logger.warn("Mod %s %s - will retry after delay", fileName, message);
                return ModReloadResult.retryNeeded(message);
            }

            // Get the plugin name and mod identifier
            String pluginName = manifest.getFullPluginName();
            modId = PluginIdentifier.fromString(pluginName);

            // Check if the mod should be excluded
            if (excludeChecker.shouldExcludeMod(fileName) || excludeChecker.shouldExcludeMod(modId)) {
                logger.info("Mod %s is excluded from reloading, skipping", fileName);
                return ModReloadResult.success();
            }

            // Create the reload state and add it to the reloading mods map
            ModReloadState reloadState = new ModReloadState();
            reloadingMods.put(modId, reloadState);

            // Get the plugin map and setup dependencies
            Map<PluginIdentifier, JavaPlugin> hytalePluginList = pluginManager.getPluginMap();
            dependencyManager.setupDependencies(manifest, reloadState, hytalePluginList, pluginName);
            ModReloadThread.setDependenciesState(modId, PluginState.SETUP);

            // Get the existing plugin
            PluginIdentifier pluginId = PluginIdentifier.fromString(pluginName);
            JavaPlugin existingPlugin = hytalePluginList.get(pluginId);

            // Ignore if the plugin is not found
            if (existingPlugin == null) {
                if (!pluginManager.loadPlugin(pluginId, pluginName)) {
                    PlayerUtil.sendMessageWithPermission(
                        Message.raw("Failed to load mod " + pluginName),
                        "mdevtools.command.plugin.reload"
                    );

                    return ModReloadResult.error("Failed to load mod");
                }
            } else {
                if (!pluginManager.reloadPlugin(pluginId, pluginName)) {
                    PlayerUtil.sendMessageWithPermission(
                        Message.raw("Failed to reload mod " + pluginName),
                        "mdevtools.command.plugin.reload"
                    );

                    return ModReloadResult.error("Failed to reload mod");
                }
            }

            /**
             * @todo after mod reload, also load all their classes in the class loader
             * since sometimes not all classes are loaded by the plugin manager
             * and if a plugin gets unloaded and uses a class that is not loaded,
             * it will cause a class not found error.
             */

            dependencyManager.restoreDependencies(reloadState, hytalePluginList, pluginName);

            logger.info("Mod %s has been reloaded", pluginName);

            // Announce the reload
            PlayerUtil.sendMessageWithPermission(
                Message.raw("Mod " + pluginName + " has been reloaded"),
                "mdevtools.command.plugin.reload"
            );

            return ModReloadResult.success();
        } catch (Exception e) {
            throw e;
        } finally {
            if (modId != null) {
                reloadingMods.remove(modId);
            }
        }
    }

    /**
     * Handle mod file deletion by disabling the plugin
     * @param filePath The path to the deleted mod file
     * @param fileName The file name for logging
     * @return The result of the deletion operation
     */
    public ModReloadResult onModFileDeleted(Path filePath, String fileName) {
        // If doesn't support deletion, return success
        if (!Main.INSTANCE.config.getBoolean("mods.reload.unloadWhenDeleted", false)) {
            logger.warn("Mod %s was deleted, but unloading is not supported, skipping", fileName);
            return ModReloadResult.success();
        }

        logger.info("Mod file %s was deleted, attempting to unload plugin", fileName);

        PluginIdentifier modId = null;

        try {
            // Get the plugin map and find the plugin identifier
            Map<PluginIdentifier, JavaPlugin> hytalePluginList = pluginManager.getPluginMap();
            modId = pluginManager.findPluginIdByPath(filePath, hytalePluginList);

            // Ignore if the plugin is not found
            if (modId == null) {
                logger.debug("Mod %s was not loaded, nothing to unload", fileName);
                return ModReloadResult.success();
            }

            // Get the plugin
            JavaPlugin plugin = hytalePluginList.get(modId);

            // Ignore if the plugin is not found
            if (plugin == null) {
                logger.warn("Plugin %s seems not to be loaded, nothing to unload", modId);
                return ModReloadResult.success();
            }

            // Unload the plugin
            PluginManager.get().unload(modId);

            logger.info("Mod %s has been unloaded", fileName);
            return ModReloadResult.success();
        } catch (Exception e) {
            logger.error("Failed to unload mod %s (%s): %t", fileName, modId, e);
            return ModReloadResult.error("Failed to unload mod: " + e.getMessage());
        }
    }
}
