package com.machina.mdevtools.events;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.machina.mdevtools.tasks.ModReloadTask;

public class PluginEvents {
    /*
     * Called when a plugin is setup
     * @param event The plugin setup event
     */
    public static void onPluginSetup(PluginSetupEvent event) {
        // Get the plugin identifier
        PluginIdentifier pluginId = event.getPlugin().getIdentifier();

        // If plugin is being reloaded
        if (ModReloadTask.isReloading(pluginId)) {
            // Call the dependency setter to set the dependency to ENABLED
            ModReloadTask.setDependenciesState(pluginId, PluginState.ENABLED);
        }
    }
}
