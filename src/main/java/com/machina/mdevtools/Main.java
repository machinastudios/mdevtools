package com.machina.mdevtools;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.ChatMessage;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.machina.mdevtools.events.CommandInterceptorEvent;
import com.machina.mdevtools.events.PluginEvents;
import com.machina.mdevtools.tasks.LogCleanupTask;
import com.machina.mdevtools.tasks.LogLevelTask;
import com.machina.mdevtools.tasks.ModReloadTask;
import com.machina.shared.SuperPlugin;
import com.machina.shared.config.ConfigurationFile;
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
    public ConfigurationFile config = new ConfigurationFile(this, "config");

    /**
     * The tasks to run
     */
    public List<Task> tasks = new ArrayList<>();

    /**
     * The logger for the plugin
     */
    public final ModLogger logger = ModLogger.forMod(this);

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        PLUGIN_INIT = init;
    }

    public void init() {
        // Save the instance
        INSTANCE = this;

        // Load the configuration
        loadConfig();

        // Initialize the tasks and load the configuration
        onConfigChanged();

        // Register the command interceptor event
        getPacketInterceptorRegistry().registerInterceptor(ChatMessage.class, CommandInterceptorEvent::onCommand);

        // Register the plugin setup event
        this.getEventRegistry().registerGlobal(PluginSetupEvent.class, PluginEvents::onPluginSetup);

        // Add the config change listener
        config.onChange(this::onConfigChanged);
    }

    public void deinit() {
        // Stop all tasks
        for (Task task : tasks) {
            logger.info("Stopping task %s", task.getName());
            task.stop();
        }

        // Clear the tasks list
        tasks.clear();
    }

    /**
     * Initialize the tasks
     */
    private void onConfigChanged() {
        deinit();

        // Add the tasks
        tasks.add(new LogCleanupTask());
        tasks.add(new ModReloadTask());
        tasks.add(new LogLevelTask());

        // Start all tasks
        for (Task task : tasks) {
            if (task.isEnabled(true)) {
                logger.info("Starting task %s", task.getName());

                try {
                    task.start();
                } catch (Exception e) {
                    logger.error("Failed to start task %s", task.getName(), e);
                }
            }
        }
    }

    /**
     * Load the configuration
     */
    private void loadConfig() {
        config.addDefault("logs.cleanupOnStartup.enabled", true, "Whether to cleanup logs and lock files on startup");

        config.addDefault("logs.global.level", "INFO", "The log level to set for all loggers. Set null to ignore\nAccepted values: FINEST, FINER, FINE, TRACE, DEBUG, INFO, WARNING, SEVERE, OFF");
        config.addDefault("logs.global.skip", List.of("PacketLogging", "WorldChunk"), "The loggers to skip when setting the log level");

        config.addDefault("mods.reload.delayMs", 1000, "Delay in milliseconds before reloading a mod after it's detected (to ensure file is fully written)");
        config.addDefault("mods.reload.fileStabilityCheckMs", 500, "Time in milliseconds to wait checking if file size is stable before reloading");

        config.addDefault("mods.reload.enabled", true, "Whether to automatically reload mods when they are updated");
        config.addDefault("mods.reload.additionalDirectories", List.of(), "Additional directories to watch for mods updates");
        config.addDefault("mods.reload.exclude", List.of(), "Mods to exclude from reloading.\nSupports wildcards and will match mod IDs (group:id) and also file names.");
        config.addDefault("mods.reload.unloadWhenDeleted", false, "Whether to unload a mod when it's deleted");

        config.load();
    }
}
