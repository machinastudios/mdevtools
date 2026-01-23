package com.machina.mdevtools.tasks.modreload;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.machina.shared.factory.ModLogger;

/**
 * Utility class for plugin management operations during mod reload.
 */
public final class ModReloadPluginManager {
    private final ModLogger logger;

    public ModReloadPluginManager(ModLogger logger) {
        this.logger = logger;
    }

    /**
     * Get the plugin map using reflection
     * @return The plugin map
     * @throws Exception if reflection fails
     */
    public Map<PluginIdentifier, JavaPlugin> getPluginMap() throws Exception {
        Field pluginsField = PluginManager.class.getDeclaredField("plugins");
        pluginsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<PluginIdentifier, JavaPlugin> pluginMap = (Map<PluginIdentifier, JavaPlugin>) pluginsField.get(PluginManager.get());
        return pluginMap;
    }

    /**
     * Find plugin identifier by file path by checking all loaded plugins
     * @param filePath The file path to search for
     * @param hytalePluginList The plugin map
     * @return The plugin identifier if found, null otherwise
     */
    public PluginIdentifier findPluginIdByPath(Path filePath, Map<PluginIdentifier, JavaPlugin> hytalePluginList) {
        Path normalizedTargetPath = ModReloadPathUtils.normalizePath(filePath);

        // Iterate over the plugin map
        for (Map.Entry<PluginIdentifier, JavaPlugin> entry : hytalePluginList.entrySet()) {
            try {
                // Get the plugin
                JavaPlugin plugin = entry.getValue();
                if (plugin == null) {
                    continue;
                }

                Path pluginPath = plugin.getFile();
                if (pluginPath == null) {
                    continue;
                }

                if (ModReloadPathUtils.normalizePath(pluginPath).equals(normalizedTargetPath)) {
                    return entry.getKey();
                }
            } catch (Exception e) {
                logger.debug("Error checking plugin path for %s: %t", entry.getKey(), e);
            }
        }
        return null;
    }

    /**
     * Load a plugin
     * @param pluginId The plugin identifier
     * @param pluginName The plugin name for logging
     * @return True if successful, false otherwise
     */
    public boolean loadPlugin(PluginIdentifier pluginId, String pluginName) {
        logger.info("Mod %s is not loaded yet, will be loaded", pluginName);

        // Load the plugin
        if (PluginManager.get().load(pluginId)) {
            logger.info("Mod %s has been loaded", pluginName);
            return true;
        } else {
            logger.error("Failed to load mod %s", pluginName);
            return false;
        }
    }

    /**
     * Reload a plugin
     * @param pluginId The plugin identifier
     * @param pluginName The plugin name for logging
     * @return True if successful, false otherwise
     */
    public boolean reloadPlugin(PluginIdentifier pluginId, String pluginName) {
        // Get the asset module
        var assetModule = AssetModule.get();
        var assetPacketExists = assetModule.getAssetPack(pluginId.toString()) != null;

        // If an asset packet for the plugin is already registered, unregister it
        // Idk why Hytale isn't doing this automatically
        if (assetPacketExists) {
            logger.info("Unregistering asset packet for mod %s", pluginName);
            logger.info("This will cause some erros in the console, please ignore them, they're harmless");

            // This will cause some erros in the console, but it's necessary
            assetModule.unregisterPack(pluginId.toString());
        }

        // Reload the plugin
        if (!PluginManager.get().reload(pluginId)) {
            logger.error("Failed to reload mod %s", pluginName);
            return false;
        }

        // If an asset packet for the plugin was registered
        if (assetModule.getAssetPack(pluginId.toString()) != null) {
            logger.info("Resending all assets to clients for mod %s", pluginName);

            // Get the asset packet files
            var assetPacketFileSystem = assetModule.getAssetPack(pluginId.toString()).getFileSystem();

            resendAllAssetsToClients(assetPacketFileSystem);
        }
        
        logger.info("Mod %s has been reloaded", pluginName);
        return true;
    }

    /**
     * Resend all assets to clients
     * @param assetPacketFileSystem The asset packet file system
     */
    private void resendAllAssetsToClients(FileSystem assetPacketFileSystem) {
        for (Path root : assetPacketFileSystem.getRootDirectories()) {
            // List the paths
            try (Stream<Path> paths = Files.list(root)) {
                // Iterate over the paths
                for (Path path : paths.toList()) {
                    // If it's a file
                    if (Files.isRegularFile(path)) {
                        // Send the asset to the clients
                        sendAssetToClients(path);
                    }
                }
            } catch (IOException e) {
                logger.error("Error listing assets: %t", e);
            }
        }
    }

    /**
     * Send an asset to the clients
     * This abuses a packet that is used to send assets to the clients out of the SETUP phase
     * Idk why this is there, but we will use it until Hytale removes it
     * @param path The path of the asset
     */
    private void sendAssetToClients(Path path) {
        final byte[] assetBytes;

        try {
            // Read the asset bytes
            assetBytes = Files.readAllBytes(path);
        } catch (IOException e) {
            logger.error("Error reading asset: %t", e);
            return;
        }

        // First, prepare the asset
        ModAsset modAsset = new ModAsset(path.toString(), assetBytes);

        // Iterate over the players
        for (PlayerRef player : Universe.get().getPlayers()) {
            // Get the ref
            var ref = player.getReference();
            var store = ref.getStore();
            var world = store.getExternalData().getWorld();

            // Run in the world thread
            CompletableFuture.runAsync(() -> {
                // Get the ref inside the world thread
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

                // If playerRef is null
                if (playerRef == null) {
                    return;
                }

                // Get the packet handler
                PacketHandler packetHandler = playerRef.getPacketHandler();

                // Create the init packet
                AssetInitialize initPacket = new AssetInitialize(modAsset.toPacket(), assetBytes.length);

                // Send the init packet
                packetHandler.writeNoCache(initPacket);

                final int maxChunkSize = 4096000;
                int offset = 0;

                // While the offset is less than the asset bytes length
                while (offset < assetBytes.length) {
                    // Get the chunk size
                    int chunkSize = Math.min(maxChunkSize, assetBytes.length - offset);

                    // Get the chunk
                    byte[] chunk = new byte[chunkSize];

                    // Copy the chunk
                    System.arraycopy(assetBytes, offset, chunk, 0, chunkSize);
                    
                    // Create the part packet
                    AssetPart partPacket = new AssetPart(chunk);

                    // Send the part packet
                    packetHandler.writeNoCache(partPacket);
                    
                    // Update the offset
                    offset += chunkSize;
                }

                // Send the finalize packet
                AssetFinalize finalizePacket = new AssetFinalize();
                packetHandler.writeNoCache(finalizePacket);
            }, world);
        }
    }
}
