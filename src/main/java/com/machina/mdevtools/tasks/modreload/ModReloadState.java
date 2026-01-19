package com.machina.mdevtools.tasks.modreload;

import java.util.ArrayList;
import java.util.List;

import com.hypixel.hytale.common.plugin.PluginIdentifier;

public record ModReloadState(PluginIdentifier pluginId, List<ModReloadDependency> dependencies) {
    /**
     * Create a new ModReloadState with retry count initialized to 0
     * @param path The path to the mod file
     * @param detectedAt Timestamp when the file change was first detected
     */
    public ModReloadState(PluginIdentifier pluginId, List<ModReloadDependency> dependencies) {
        this.pluginId = pluginId;
        this.dependencies = dependencies;
    }

    /**
     * Create a new ModReloadState with no dependencies
     * @param pluginId The plugin identifier
     */
    public ModReloadState() {
        this(null, new ArrayList<>());
    }
}
