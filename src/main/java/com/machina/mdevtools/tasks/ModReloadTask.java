package com.machina.mdevtools.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Whether the task is running
     */
    private boolean running = true;

    /**
     * List of pending plugins paths to reload
     * This is used for polling
     */
    private Set<Path> pendingPlugins = new HashSet<>();

    /**
     * The logger for the task
     */
    private final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadTask");

    public ModReloadTask() {
        super("Mod Reload Task");
    }

    @Override
    public void run() {
        // Watch for .zip and .jar files in the `mods` and `builtin` directories
        Path modsPath = new File("mods").toPath();
        Path builtinPath = new File("builtin").toPath();

        // Create a hybrid watcher for the `mods` and `builtin` directories
        HybridWatcher hybridWatcher = new HybridWatcher(List.of(modsPath, builtinPath), Duration.ofMillis(300));

        while (running && !Thread.currentThread().isInterrupted()) {
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

                // Poll the file path
                pendingPlugins.add(path);
            }

            // Iterate over the pending plugins and reload them
            for (Path path : pendingPlugins) {
                try {
                    // Pay attention that this method can throw an exception or error
                    reloadMod(path);
                } catch (Throwable e) {
                    logger.error("Exception reloading mod %s: %t", path.getFileName().toString(), e);
                }
            }

            // Clear the pending plugins
            pendingPlugins.clear();
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

            // List of fake plugins to remove after reloading
            Map<PluginIdentifier, PluginState> changedPlugins = new HashMap<>();

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
                    changedPlugins.put(pluginId, null);

                    logger.debug("Dependency %s fake plugin created", dependency);
                } else {
                    // Get the state
                    Field stateField = PluginBase.class.getDeclaredField("state");
                    stateField.setAccessible(true);
                    PluginState state = (PluginState) stateField.get(pluginBase);

                    // Add the plugin to the list
                    changedPlugins.put(pluginId, state);

                    // Set the state to SETUP
                    stateField.set(pluginBase, PluginState.SETUP);

                    logger.debug("Dependency %s state set to SETUP", dependency);
                }
            }

            // Perform the "plugin reload <pluginName>" command
            PluginManager.get().reload(PluginIdentifier.fromString(pluginName));

            logger.info("Mod %s has been reloaded", pluginName);

            // Iterate over the changed plugins and set the state back
            for (Map.Entry<PluginIdentifier, PluginState> entry : changedPlugins.entrySet()) {
                // Get the plugin identifier
                PluginIdentifier pluginId = entry.getKey();

                // Get the state
                PluginState state = entry.getValue();

                logger.debug("Dependency %s state: %s", pluginId.toString(), state);

                // If the state is null, it means the fake plugin was created
                if (state == null) {
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
                stateField.set(pluginBase, state);

                logger.debug("Dependency %s state set back to %s", pluginId.toString(), state);
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
