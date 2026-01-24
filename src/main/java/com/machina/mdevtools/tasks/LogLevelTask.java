package com.machina.mdevtools.tasks;

import java.lang.reflect.Field;
import java.util.List;
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
     * The loggers to skip
     * These are loggers that are too verbose and should not be set to the log level
     */
    private List<String> loggersToSkip;

    /**
     * The log level task
     */
    public LogLevelTask() {
        super("LogLevelTask", "Sets the log level", "logLevel");
    }

    /**
     * Check if the log level task is enabled
     * @param defaultValue The default value to return if the log level is not set
     * @return True if the log level task is enabled, false otherwise
     */
    public boolean isEnabled(boolean defaultValue) {
        return super.isEnabled(defaultValue) && getLogLevel() != null;
    }

    /**
     * Start the log level task
     */
    @Override
   public void start() {
        // Get the loggers to skip from the configuration
        loggersToSkip = Main.INSTANCE.config.getStringList("logs.global.skip", List.of());

        // Parse the log level
        Level newLevel = getLogLevel();

        try {
            // Get the cached loggers field
            Field cachedLoggersField = HytaleLogger.class.getDeclaredField("CACHE");

            // Make the fields accessible
            cachedLoggersField.setAccessible(true);

            // Get the cached loggers
            Map<String, HytaleLogger> cachedLoggers = (Map<String, HytaleLogger>) cachedLoggersField.get(null);

            // Set the log level for all loggers
            for (HytaleLogger logger : cachedLoggers.values()) {
                // Skip the logger if it is in the list of loggers to skip
                if (loggersToSkip.contains(logger.getName())) {
                    continue;
                }

                // Set the log level
                logger.setLevel(newLevel);
            }
        } catch (Exception e) {
            logger.error("Failed to set the log level", e);
        }
    }

    /**
     * Get the log level from the configuration
     * @return The log level
     */
    private Level getLogLevel() {
        String logLevel = Main.INSTANCE.config.getString("logs.global.level", "INFO");

        try {
            return Level.parse(logLevel);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid log level: %s", logLevel, e);
            return Level.INFO;
        }
    }
}
