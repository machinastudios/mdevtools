package com.machina.mdevtools.tasks.modreload;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginState;

/**
 * Fake plugin to reload the plugin
 */
public class FakeModulePlugin extends JavaPlugin {
    public FakeModulePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public PluginState getState() {
        return PluginState.SETUP;
    }
}
