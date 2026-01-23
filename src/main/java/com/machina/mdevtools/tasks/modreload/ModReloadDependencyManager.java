package com.machina.mdevtools.tasks.modreload;

import java.lang.reflect.Field;
import java.util.Map;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.ModJarUtils;

/**
 * Utility class for managing plugin dependencies during mod reload.
 */
public final class ModReloadDependencyManager {
    private final ModLogger logger;

    public ModReloadDependencyManager(ModLogger logger) {
        this.logger = logger;
    }

    /**
     * Setup dependencies for a mod reload
     * @param manifest The mod manifest
     * @param reloadState The reload state to populate
     * @param hytalePluginList The plugin map
     * @param pluginName The plugin name for logging
     */
    public void setupDependencies(
        ModJarUtils.ModManifest manifest,
        ModReloadState reloadState,
        Map<PluginIdentifier, JavaPlugin> hytalePluginList,
        String pluginName
    ) throws Exception {
        // Get the dependencies names list
        var dependenciesNamesList = manifest.getDependenciesNamesList();

        // Iterate over the dependencies
        for (String dependency : dependenciesNamesList) {
            // Get the plugin identifier
            PluginIdentifier pluginId = PluginIdentifier.fromString(dependency);

            // Get the plugin base
            PluginBase pluginBase = hytalePluginList.get(pluginId);

            // Create the reload dependency
            ModReloadDependency reloadDependency = new ModReloadDependency();

            // Ignore if the plugin is not found
            if (pluginBase == null) {
                // Create a fake plugin
                pluginBase = new FakeModulePlugin(Main.PLUGIN_INIT);
                hytalePluginList.put(pluginId, (JavaPlugin) pluginBase);
                logger.debug("[%s] Dependency %s fake plugin created", pluginName, dependency);
                reloadDependency.originalState = null;
                reloadDependency.isFakeDependency = true;
            } else {
                Field stateField = PluginBase.class.getDeclaredField("state");
                stateField.setAccessible(true);
                PluginState state = (PluginState) stateField.get(pluginBase);
                reloadDependency.originalState = state;
                reloadDependency.isFakeDependency = false;
            }

            reloadDependency.pluginRef = pluginBase;
            reloadDependency.dependencyName = dependency;
            reloadState.dependencies().add(reloadDependency);
        }
    }

    /**
     * Restore dependencies to their original state
     * @param reloadState The reload state containing dependencies
     * @param hytalePluginList The plugin map
     * @param pluginName The plugin name for logging
     */
    public void restoreDependencies(
        ModReloadState reloadState,
        Map<PluginIdentifier, JavaPlugin> hytalePluginList, 
        String pluginName
    ) throws Exception {
        // Iterate over the dependencies
        for (ModReloadDependency dependency : reloadState.dependencies()) {
            PluginIdentifier pluginId = dependency.getDependencyNameAsIdentifier();
            PluginState originalState = dependency.originalState;

            logger.debug("[%s] Dependency %s original state: %s", pluginName, pluginId.toString(), originalState);

            if (dependency.isFakeDependency) {
                hytalePluginList.remove(pluginId);
                logger.debug("[%s] Dependency %s fake plugin removed", pluginName, pluginId.toString());
                continue;
            }

            PluginBase pluginBase = hytalePluginList.get(pluginId);
            Field stateField = PluginBase.class.getDeclaredField("state");
            stateField.setAccessible(true);
            stateField.set(pluginBase, originalState);

            logger.debug("[%s] Dependency %s state set back to %s", pluginName, pluginId.toString(), originalState);
        }
    }
}
