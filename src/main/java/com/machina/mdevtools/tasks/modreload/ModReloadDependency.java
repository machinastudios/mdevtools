package com.machina.mdevtools.tasks.modreload;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginState;

public class ModReloadDependency {
    /**
     * The plugin reference
     */
    public PluginBase pluginRef;

    /**
     * The name of the dependency
     */
    public String dependencyName;

    /**
     * If the dependency is a fake dependency
     */
    public boolean isFakeDependency;

    /**
     * The original state of the dependency
     */
    public PluginState originalState;

    /**
     * Get the dependency name as identifier
     * @return The dependency name as identifier
     */
    public PluginIdentifier getDependencyNameAsIdentifier() {
        return PluginIdentifier.fromString(dependencyName);
    }
}