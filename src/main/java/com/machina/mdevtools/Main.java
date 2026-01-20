package com.machina.mdevtools;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.machina.mdevtools.events.PluginEvents;
import com.machina.mdevtools.tasks.ModReloadTask;
import com.machina.shared.SuperPlugin;
import com.machina.shared.config.ModConfig;
import com.machina.shared.factory.ModLogger;

/**
 * Internal test plugin for MInterfaceBuilder
 */
public class Main extends SuperPlugin {
    /**
     * The singleton instance of the Main class
     */
    public static Main INSTANCE;

    /**
     * The plugin initialization
     */
    public static JavaPluginInit PLUGIN_INIT;

    /**
     * The plugin configuration
     */
    public ModConfig config = new ModConfig(this, "config");

    /**
     * The tasks to run
     */
    public List<Thread> tasks = new ArrayList<>();

    /**
     * The logger for the plugin
     */
    public final ModLogger logger = ModLogger.forMod(this);

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        PLUGIN_INIT = init;
    }

    public void start() {
        // Save the instance
        INSTANCE = this;
        
        // Load the configuration
        loadConfig();

        runIfEnabled("logs.cleanupOnStartup", "Logs and lock files cleanup on startup is %s", this::startLogCleanupTask);
        runIfEnabled("mods.restartServerWhenUpdated", "Restart server when mods are updated is %s", this::startModReloadTask);

        // Register the plugin setup event
        this.getEventRegistry().registerGlobal(PluginSetupEvent.class, PluginEvents::onPluginSetup);
    }

    public void stop() {
        // Stop all tasks
        for (Thread task : tasks) {
            task.interrupt();
        }
    }

    /**
     * Load the configuration
     */
    private void loadConfig() {
        config.addDefault("logs.cleanupOnStartup", true, "Whether to cleanup logs and lock files on startup");

        config.addDefault("mods.reloadDelayMs", 1000, "Delay in milliseconds before reloading a mod after it's detected (to ensure file is fully written)");
        config.addDefault("mods.fileStabilityCheckMs", 500, "Time in milliseconds to wait checking if file size is stable before reloading");
        config.addDefault("mods.unloadWhenDeleted", false, "Whether to unload a mod when it's deleted");
    
        config.load();
    }

    /**
     * Cleanup logs and lock files on startup
     */
    private void startLogCleanupTask() {
        // Cleanup all logs and lock files but the last ones
        // Log files have .log and .log.lck extensions
        File logDir = new File("logs");
        
        if (logDir.exists()) {
            File[] files = logDir.listFiles();

            // Check if files is null
            if (files == null) {
                return;
            }

            // Get the two last file names ordering by name
            String[] lastFileNames = Arrays.stream(files)
                .filter(file -> file.isFile() && (file.getName().endsWith(".log") || file.getName().endsWith(".log.lck")))
                .sorted(Comparator.comparing(File::getName, Comparator.reverseOrder()))
                .limit(2)
                .map(File::getName)
                .toArray(String[]::new);

            // Check if lastFileNames is null
            if (lastFileNames == null) {
                return;
            }

            // Check if lastFileNames has two elements
            if (lastFileNames.length != 2) {
                return;
            }

            // Iterate over all files
            for (File file : files) {
                // Check if file is not the last two files
                if (Arrays.asList(lastFileNames).contains(file.getName())) {
                    continue;
                }

                // Check if file is a file and has a .log or .log.lck extension
                if (file.isFile() && (file.getName().endsWith(".log") || file.getName().endsWith(".log.lck"))) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Reload mods when they are updated
     */
    private void startModReloadTask() {
        // Add a task to watch for mods updates
        startTask(new ModReloadTask());
    }

    /**
     * Run a task if a configuration key is enabled
     * @param configKey The configuration key
     * @param message The message to log where %s will be replaced with `enabled` or `disabled`
     * @param runnable The task to run
     */
    private void runIfEnabled(String configKey, String message, Runnable runnable) {
        boolean enabled = config.getBoolean(configKey, true);
        logger.info(message, enabled ? "enabled" : "disabled");

        if (enabled) {
            runnable.run();
        }
    }

    /**
     * Start a task
     * @param task The task to start
     */
    private void startTask(@Nonnull Thread task) {
        tasks.add(Thread.ofPlatform().start(task));
    }
}
