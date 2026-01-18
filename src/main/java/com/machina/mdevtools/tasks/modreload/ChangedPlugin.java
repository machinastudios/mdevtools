package com.machina.mdevtools.tasks.modreload;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginState;

/**
 * Record representing a plugin that had its state changed during reload
 * @param pluginId The plugin identifier
 * @param originalState The original plugin state (null if fake plugin was created)
 */
public record ChangedPlugin(PluginIdentifier pluginId, PluginState originalState) {}