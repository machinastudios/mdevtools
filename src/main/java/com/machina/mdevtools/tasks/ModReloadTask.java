package com.machina.mdevtools.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.util.HybridWatcher;

import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.ModJarUtils;

public class ModReloadTask extends Thread {
    /**
     * Record representing a pending mod that is waiting to be reloaded
     * @param path The path to the mod file
     * @param detectedAt Timestamp when the file change was first detected
     */
    private record PendingMod(Path path, long detectedAt) {}

    /**
     * Record representing a plugin that had its state changed during reload
     * @param pluginId The plugin identifier
     * @param originalState The original plugin state (null if fake plugin was created)
     */
    private record ChangedPlugin(PluginIdentifier pluginId, PluginState originalState) {}

    /**
     * Whether the task is running
     */
    private boolean running = true;

    /**
     * Map of pending plugins waiting to be reloaded
     * Key: Path to the mod file (for fast lookup)
     * Value: PendingMod record with path and detection timestamp
     */
    private Map<Path, PendingMod> pendingPlugins = new HashMap<>();

    /**
     * The logger for the task
     */
    private final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadTask");

    /**
     * Delay in milliseconds before reloading a mod after it's detected
     */
    private long reloadDelayMs;

    /**
     * Time in milliseconds to wait checking if file size is stable before reloading
     */
    private long fileStabilityCheckMs;
    
    /**
     * Whether configuration has been initialized
     */
    private boolean configInitialized = false;

    public ModReloadTask() {
        super("Mod Reload Task");
        
        // Load configuration values (will be set in first run() iteration if needed)
        this.reloadDelayMs = 1000; // Default value, will be overridden in run()
        this.fileStabilityCheckMs = 500; // Default value, will be overridden in run()
    }
    
    /**
     * Initialize configuration values from Main.INSTANCE.config
     */
    private void initConfig() {
        if (!configInitialized && Main.INSTANCE != null && Main.INSTANCE.config != null) {
            try {
                reloadDelayMs = Main.INSTANCE.config.getLong("mods.reloadDelayMs", 1000);
                fileStabilityCheckMs = Main.INSTANCE.config.getLong("mods.fileStabilityCheckMs", 500);
                
                configInitialized = true;
                logger.debug(
                    "ModReloadTask config loaded: reloadDelayMs=%d, fileStabilityCheckMs=%d", 
                    reloadDelayMs,
                    fileStabilityCheckMs
                );
            } catch (Exception e) {
                logger.warn("Failed to load config: %t", e);
            }
        }
    }

    @Override
    public void run() {
        // Initialize configuration
        initConfig();
        
        // Watch for .zip and .jar files in the `mods` and `builtin` directories
        Path modsPath = new File("mods").toPath();
        Path builtinPath = new File("builtin").toPath();

        // Create a hybrid watcher for the `mods` and `builtin` directories
        HybridWatcher hybridWatcher = new HybridWatcher(List.of(modsPath, builtinPath), Duration.ofMillis(300));

        while (running && !Thread.currentThread().isInterrupted()) {
            // Re-initialize config in case it wasn't loaded yet
            initConfig();
            // Get the next entry
            List<HybridWatcher.Entry> entries = hybridWatcher.poll();

            for (HybridWatcher.Entry entry : entries) {
                // Get the path of the entry
                Path path = entry.path();

                // Get the type of the entry
                HybridWatcher.EventType type = entry.type();

                // Check if the file is a .zip or .jar file
                boolean isZip = path.getFileName().toString().endsWith(".zip");
                boolean isJar = path.getFileName().toString().endsWith(".jar");
                boolean isMod = isZip || isJar;

                // Check if the file is a .zip or .jar file
                if (!isMod) {
                    logger.debug("File %s is not a mod, skipping", path.getFileName().toString());
                    continue;
                }

                logger.info("Mod %s has been changed, will be reloaded", path.getFileName().toString());

                // Add the file to pending list with current timestamp if not already present
                long now = System.currentTimeMillis();
                if (!pendingPlugins.containsKey(path)) {
                    pendingPlugins.put(path, new PendingMod(path, now));

                    logger.debug(
                        "Added mod %s to pending list, waiting for delay and stability check", 
                        path.getFileName().toString()
                    );
                } else {
                    // File was already detected, update timestamp (file is still being written)
                    pendingPlugins.put(path, new PendingMod(path, now));
                    logger.debug(
                        "Mod %s still being written, resetting wait timer", 
                        path.getFileName().toString()
                    );
                }
            }

            // Iterate over the pending plugins and check if they're ready to reload
            long now = System.currentTimeMillis();
            List<Path> readyToReload = new ArrayList<>();
            
            for (PendingMod pendingMod : pendingPlugins.values()) {
                Path path = pendingMod.path();
                long detectedAt = pendingMod.detectedAt();
                
                // Check if enough time has passed since detection
                long timeSinceDetection = now - detectedAt;
                if (timeSinceDetection < reloadDelayMs) {
                    logger.debug(
                        "Mod %s not ready yet: %d ms since detection (need %d ms)", 
                        path.getFileName().toString(), timeSinceDetection, reloadDelayMs
                    );

                    continue;
                }
                
                // Check if file is stable (size hasn't changed)
                if (!isFileStable(path)) {
                    logger.debug(
                        "Mod %s size is still changing, waiting for stability", 
                        path.getFileName().toString()
                    );

                    continue;
                }
                
                // File is ready to reload
                readyToReload.add(path);
            }

            // Reload the ready plugins
            for (Path path : readyToReload) {
                try {
                    // Pay attention that this method can throw an exception or error
                    reloadMod(path);
                } catch (Throwable e) {
                    logger.error("Exception reloading mod %s: %t", path.getFileName().toString(), e);
                } finally {
                    // Remove from pending list regardless of success/failure
                    pendingPlugins.remove(path);
                }
            }
            
            // Small sleep to avoid busy waiting
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Check if a file is stable (size hasn't changed in the configured time)
     * @param filePath The path to the file
     * @return True if the file is stable, false otherwise
     */
    private boolean isFileStable(Path filePath) {
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

    /**
     * Reload a mod
     * @param filePath The path to the mod file
     */
    private synchronized void reloadMod(Path filePath) throws Exception {
        logger.info("Reloading mod %s", filePath.getFileName().toString());

        try {
            // Get the manifest of the mod
            ModJarUtils.ModManifest manifest = ModJarUtils.getModManifest(filePath);

            // If the manifest is null, the mod is not a valid mod, skip
            if (manifest == null) {
                throw new Exception("Mod is not a valid mod, manifest.json file is missing or invalid");
            }

            // Get the full plugin name
            String pluginName = manifest.getFullPluginName();

            // Get the dependencies names list
            List<String> dependenciesNamesList = manifest.getDependenciesNamesList();

            // BEFORE reloading the plugin, we need to do a trick to make some internals
            // that are buggy right now work

            // Get the "plugins" field
            Field pluginsField = PluginManager.class.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            Map<PluginIdentifier, JavaPlugin> plugins = (Map<PluginIdentifier, JavaPlugin>) pluginsField.get(PluginManager.get());

            // List of plugins that had their state changed during reload
            List<ChangedPlugin> changedPlugins = new ArrayList<>();

            // Iterate over the dependencies list and fake them
            for (String dependency : dependenciesNamesList) {
                // Create a fake plugin identifier
                PluginIdentifier pluginId = PluginIdentifier.fromString(dependency);

                // Get if exists
                PluginBase pluginBase = plugins.get(pluginId);

                // If it doesn't exist, create it
                if (pluginBase == null) {
                    pluginBase = new FakeModulePlugin(Main.PLUGIN_INIT);

                    // Add the fake plugin to the plugins map
                    plugins.put(pluginId, (JavaPlugin) pluginBase);

                    // Add the fake plugin to the list (null means fake plugin was created)
                    changedPlugins.add(new ChangedPlugin(pluginId, null));

                    logger.debug("Dependency %s fake plugin created", dependency);
                } else {
                    // Get the state
                    Field stateField = PluginBase.class.getDeclaredField("state");
                    stateField.setAccessible(true);
                    PluginState state = (PluginState) stateField.get(pluginBase);

                    // Add the plugin to the list
                    changedPlugins.add(new ChangedPlugin(pluginId, state));

                    // Set the state to SETUP
                    stateField.set(pluginBase, PluginState.SETUP);

                    logger.debug("Dependency %s state set to SETUP", dependency);
                }
            }

            // Perform the "plugin reload <pluginName>" command
            PluginManager.get().reload(PluginIdentifier.fromString(pluginName));

            logger.info("Mod %s has been reloaded", pluginName);

            // Iterate over the changed plugins and set the state back
            for (ChangedPlugin changedPlugin : changedPlugins) {
                PluginIdentifier pluginId = changedPlugin.pluginId();
                PluginState originalState = changedPlugin.originalState();

                logger.debug("Dependency %s original state: %s", pluginId.toString(), originalState);

                // If the state is null, it means the fake plugin was created
                if (originalState == null) {
                    // Remove the fake plugin from the plugins map
                    plugins.remove(pluginId);

                    logger.debug("Dependency %s fake plugin removed", pluginId.toString());
                    continue;
                }

                // Get the plugin base
                PluginBase pluginBase = plugins.get(pluginId);

                // Set the state back
                Field stateField = PluginBase.class.getDeclaredField("state");
                stateField.setAccessible(true);
                stateField.set(pluginBase, originalState);

                logger.debug("Dependency %s state set back to %s", pluginId.toString(), originalState);
            }
        } catch (Exception e) {
            logger.error("Exception reloading mod %s: %t", filePath.getFileName().toString(), e);
            throw e;
        }
    }
}

/**
 * Fake plugin to reload the plugin
 */
class FakeModulePlugin extends JavaPlugin {
    public FakeModulePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public PluginState getState() {
        return PluginState.SETUP;
    }
}
