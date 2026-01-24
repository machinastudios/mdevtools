package com.machina.mdevtools.tasks;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.Task;
import com.machina.shared.factory.ModLogger;

public class LogLevelTask extends Task {
    /**
     * The logger for the log level task
     */
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "LogLevelTask");

    /**
     * The log level to set
     */
    private static String logLevel = "INFO";

    /**
     * The log level task
     */
    public LogLevelTask() {
        super("LogLevelTask", "Sets the log level", "logLevel");
    }

    /**
     * Start the log level task
     */
    @Override
   public void start() {
        // Get the log level from the configuration
        String logLevel = Main.INSTANCE.config.getString("logs.global.level", "INFO");

        // Parse the log level
        Level newLevel;

        try {
            newLevel = Level.parse(logLevel);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid log level: %s", logLevel, e);
            return;
        }

        try {
            // Get the cached loggers field
            Field cachedLoggersField = HytaleLogger.class.getDeclaredField("CACHE");

            // Make the fields accessible
            cachedLoggersField.setAccessible(true);

            // Get the cached loggers
            Map<String, HytaleLogger> cachedLoggers = (Map<String, HytaleLogger>) cachedLoggersField.get(null);

            // Set the log level for all loggers
            for (HytaleLogger logger : cachedLoggers.values()) {
                // Set the log level
                logger.setLevel(newLevel);
            }
        } catch (Exception e) {
            logger.error("Failed to set the log level", e);
        }
    }
}
