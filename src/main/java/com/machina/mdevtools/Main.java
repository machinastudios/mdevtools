package com.machina.mdevtools;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginInit;
import com.machina.mdevtools.tasks.ModReloadTask;
import com.machina.shared.SuperPlugin;
import com.machina.shared.config.PluginConfig;

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
    public PluginConfig config = new PluginConfig(this, "config");

    /**
     * The tasks to run
     */
    public List<Thread> tasks = new ArrayList<>();

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        PLUGIN_INIT = init;
    }

    public void init() {
        // Save the instance
        INSTANCE = this;
        
        // Load the configuration
        this.loadConfig();

        this.startLogCleanupTask();
        this.startModReloadTask();
    }

    public void shutdown() {
        super.shutdown();

        // Stop all tasks
        for (Thread task : this.tasks) {
            task.interrupt();
        }
    }

    /**
     * Load the configuration
     */
    private void loadConfig() {
        this.config.addDefault("logs.cleanupOnStartup", true, "Whether to cleanup logs and lock files on startup");
        this.config.addDefault("mods.restartServerWhenUpdated", true, "Whether to restart the server when mods are updated");
    
        this.config.load();
    }

    /**
     * Cleanup logs and lock files on startup
     */
    private void startLogCleanupTask() {
        // Check if cleanup on startup is enabled
        if (!this.config.getBoolean("logs.cleanupOnStartup", true)) {
            return;
        }

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
        // Check if reload when updated is enabled
        if (!this.config.getBoolean("mods.restartServerWhenUpdated", true)) {
            return;
        }

        // Add a task to watch for mods updates
        this.startTask(new ModReloadTask());
    }

    /**
     * Start a task
     * @param task The task to start
     */
    private void startTask(@Nonnull Thread task) {
        this.tasks.add(Thread.ofPlatform().start(task));
    }
}
