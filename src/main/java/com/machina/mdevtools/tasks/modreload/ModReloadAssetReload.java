package com.machina.mdevtools.tasks.modreload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetRegistryLoader;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.Colors;
import com.machina.shared.util.PlayerUtil;

public class ModReloadAssetReload {
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadAssetReload");

    /**
     * Resend all assets to clients
     * @param assetPacketFileSystem The asset packet file system
     */
    public static void reloadAssetPacket(PluginIdentifier pluginId) {
        // Get the asset module
        AssetModule assetModule = AssetModule.get();

        // Try to get the asset packet
        // @todo this is returning null, so we will need to load the asset packet first
        var assetPack = assetModule.getAssetPack(pluginId.toString());

        // If an asset packet for the plugin was registered
        if (assetPack == null) {
            logger.debug("No asset packet for mod %s found", pluginId.toString());
            return;
        }

        // Load the assets
        // `event` can be null, and Hytale will handle it correctly
        AssetRegistryLoader.loadAssets(null, assetPack);

        // Get the asset store map
        var assetStoremap = AssetRegistry.getStoreMap();

        // List of paths to check
        List<String> pathesToCheckList = new ArrayList<>();

        // Iterate over the asset store map
        for (Map.Entry<?, AssetStore<?, ?, ?>> entry : assetStoremap.entrySet()) {
            pathesToCheckList.add(entry.getValue().getPath());
        }

        // Add the "Server" directory to the list
        pathesToCheckList.add("Server");

        // Find the asset store for the plugin
        List<Path> assetPaths = new ArrayList<>();

        // Get the root path of the asset pack
        var root = assetPack.getRoot();

        // Check only the valid for the mod itself
        for (String path : pathesToCheckList) {
            // Resolve the asset path
            Path assetPath = root.resolve(path);

            // If the path is not a directory, continue
            if (!Files.isDirectory(assetPath, new LinkOption[0])) {
                continue;
            }

            // Send the asset to the clients
            sendAssetsToClients(assetPath, pluginId);
        }
    }

    /**
     * Send the assets to the clients
     * @param assetPath The path of the asset
     * @param pluginId The plugin identifier
     */
    private static void sendAssetsToClients(Path assetPath, PluginIdentifier pluginId) {
        try {
            // List all files including subdirectories
            List<Path> files = Files.walk(assetPath).toList();

            // List of assets to send
            List<Path> filesToSend = new ArrayList<>();

            // Iterate over the files
            for (Path file : files) {
                // If the file is a directory, continue
                if (Files.isDirectory(file, new LinkOption[0])) {
                    continue;
                }

                // Send the asset to the clients
                filesToSend.add(file);
            }

            logger.info("Sending %d assets to the clients", filesToSend.size());

            // Iterate over the files to send
            // DO NOT do this inside the loop above, it will lock the world thread
            for (Path file : filesToSend) {
                // Send the asset to the clients
                sendAssetToClients(file, pluginId);
            }

            logger.info("Assets sent to the clients");
        } catch (IOException e) {
            logger.error("Error listing files: %t", e);
        }
    }

    /**
     * Send an asset to the clients
     * This abuses a packet that is used to send assets to the clients out of the SETUP phase
     * Idk why this is there, but we will use it until Hytale removes it
     * @param path The path of the asset
     */
    private static void sendAssetToClients(Path path, PluginIdentifier pluginId) {
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

                // If player is OP
                if (PlayerUtil.isOp(playerRef)) {
                    PlayerUtil.sendPluginMessage(
                        playerRef,
                        Main.INSTANCE,
                        Message.raw("Receiving asset: " + path.toString() + " from mod " + pluginId.toString()).color(Colors.LIGHT_GRAY)
                    );
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

                // If player is OP
                if (PlayerUtil.isOp(playerRef)) {
                    PlayerUtil.sendPluginMessage(
                        playerRef,
                        Main.INSTANCE, 
                        Message.raw("Received asset: " + path.toString() + " from mod " + pluginId.toString()).color(Colors.LIGHT_GRAY)
                    );
                }
            }, world);
        }
    }
}
