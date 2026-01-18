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


public class ModReloadTask extends Thread {
    private boolean running = true;

    public ModReloadTask() {
        super("Mod Reload Task");
    }

    @Override
    public void run() {
        // Watch for .zip and .jar files in the `mods` and `builtin` directories
        Path modsPath = new File("mods").toPath();
        Path builtinPath = new File("builtin").toPath();

        try {
            // Create a watch service
            WatchService watchService = FileSystems.getDefault().newWatchService();

            // Register the mods path for watching
            modsPath.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            );

            // Register the builtin path for watching
            builtinPath.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            );

            // Start the watch loop
            while (running && !Thread.currentThread().isInterrupted()) {
                // Take a key from the watch service
                WatchKey key = watchService.take();

                // Process events for this key
                for (WatchEvent<?> event : key.pollEvents()) {
                    // Get the kind of the event
                    WatchEvent.Kind<?> kind = event.kind();

                    // Check if the event is a file
                    if (!(event.context() instanceof Path)) {
                        continue;
                    }

                    if (
                        kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_DELETE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY
                    ) {
                        // Check if the event is a file
                        if (!(event.context() instanceof Path)) {
                            continue;
                        }

                        // Get the parent path of the event
                        Path parentPath = (Path) key.watchable();

                        // Get the file path including the directory
                        Path filePath = parentPath.resolve((Path) event.context());

                        boolean isZip = filePath.getFileName().toString().endsWith(".zip");
                        boolean isJar = filePath.getFileName().toString().endsWith(".jar");
                        boolean isMod = isZip || isJar;

                        // Check if the file is a .zip or .jar file
                        if (!isMod) {
                            continue;
                        }

                        reloadMod(filePath);
                    }
                }

                // Reset the key to receive further events
                boolean valid = key.reset();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reload a mod
     * @param filePath The path to the mod file
     */
    private void reloadMod(Path filePath) {
        try {
            // Open the .jar or .zip file to look for the manifest.json file
            InputStream inputStream = new FileInputStream(filePath.toFile());
            ZipInputStream zipInputStream = new ZipInputStream(inputStream);
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
                if (zipEntry.getName().equals("manifest.json")) {
                    break;
                }
                zipEntry = zipInputStream.getNextEntry();
            }

            // Read the manifest.json file
            BufferedReader reader = new BufferedReader(new InputStreamReader(zipInputStream));
            String contentsString = "";
            String line;
            while ((line = reader.readLine()) != null) {
                contentsString += line;
            }

            // Close the streams
            inputStream.close();
            zipInputStream.close();
            reader.close();

            // Parse using Gson
            JsonObject manifest = new Gson().fromJson(contentsString, JsonObject.class);

            // Get the group and name from the manifest
            String group = manifest.get("Group").getAsString();
            String name = manifest.get("Name").getAsString();

            // Get the "Dependencies" and "OptionalDependencies" fields
            JsonObject dependencies = manifest.get("Dependencies").getAsJsonObject();
            JsonObject optionalDependencies = manifest.get("OptionalDependencies").getAsJsonObject();

            List<String> dependenciesList = new ArrayList<>();

            for (Map.Entry<String, JsonElement> entry : dependencies.entrySet()) {
                dependenciesList.add(entry.getKey());
            }

            for (Map.Entry<String, JsonElement> entry : optionalDependencies.entrySet()) {
                dependenciesList.add(entry.getKey());
            }

            // Get the full plugin name
            String pluginName = group + ":" + name;

            // BEFORE reloading the plugin, we need to do a trick to make some internals
            // that are buggy right now work

            // Get the "plugins" field
            Field pluginsField = PluginManager.class.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            Map<PluginIdentifier, JavaPlugin> plugins = (Map<PluginIdentifier, JavaPlugin>) pluginsField.get(PluginManager.get());

            // List of fake plugins to remove after reloading
            Map<PluginIdentifier, PluginState> changedPlugins = new HashMap<>();

            // Iterate over the dependencies list and fake them
            for (String dependency : dependenciesList) {
                // Create a fake plugin identifier
                PluginIdentifier fakePluginId = PluginIdentifier.fromString(dependency);

                // Get if exists
                PluginBase pluginBase = plugins.get(fakePluginId);

                // If it doesn't exist, create it
                if (pluginBase == null) {
                    pluginBase = new FakeModulePlugin(Main.PLUGIN_INIT);

                    // Add the fake plugin to the plugins map
                    plugins.put(fakePluginId, (JavaPlugin) pluginBase);

                    // Add the fake plugin to the list (null means fake plugin was created)
                    changedPlugins.put(fakePluginId, null);
                } else {
                    // Get the state
                    Field stateField = PluginBase.class.getDeclaredField("state");
                    stateField.setAccessible(true);
                    PluginState state = (PluginState) stateField.get(pluginBase);

                    // Add the plugin to the list
                    changedPlugins.put(fakePluginId, state);

                    // Set the state to SETUP
                    stateField.set(pluginBase, PluginState.SETUP);
                    
                }
            }

            // Perform the "plugin reload <pluginName>" command
            PluginManager.get().reload(PluginIdentifier.fromString(pluginName));

            // Iterate over the changed plugins and set the state back
            for (Map.Entry<PluginIdentifier, PluginState> entry : changedPlugins.entrySet()) {
                // Get the plugin identifier
                PluginIdentifier pluginId = entry.getKey();

                // Get the state
                PluginState state = entry.getValue();

                // If the state is null, it means the fake plugin was created
                if (state == null) {
                    // Remove the fake plugin from the plugins map
                    plugins.remove(pluginId);
                    continue;
                }

                // Get the plugin base
                PluginBase pluginBase = plugins.get(pluginId);

                // Set the state back
                Field stateField = PluginBase.class.getDeclaredField("state");
                stateField.setAccessible(true);
                stateField.set(pluginBase, state);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
