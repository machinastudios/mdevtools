package com.machina.mdevtools;

public abstract class Task {
    /**
     * The name of the task
     */
    private String name;

    /**
     * The description of the task
     */
    private String description;

    /**
     * The configuration key of the task
     */
    private String configKey;

    /**
     * The task constructor
     */
    public Task(String name, String description, String configKey) {
        this.name = name;
        this.description = description;
        this.configKey = configKey;
    }

    /**
     * Check if the task is enabled
     * @return True if the task is enabled, false otherwise
     */
    public boolean isEnabled(boolean defaultValue) {
        return Main.INSTANCE.config.getBoolean(configKey, defaultValue);
    }

    /**
     * Get the name of the task
     * @return The name of the task
     */
    public String getName() {
        return name;
    }

    /**
     * Get the description of the task
     * @return The description of the task
     */
    public String getDescription() {
        return description;
    }

    /**
     * Start the task
     */
    public abstract void start();

    /**
     * Stop the task
     */
    public void stop() {}
}
