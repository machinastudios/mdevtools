package com.machina.mdevtools.tasks.modreload;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

        // Get the asset store map
        var immutableAssetStoreMap = AssetRegistry.getStoreMap();

        // Declare variables that will be used later
        Map<Object, AssetStore<?, ?, ?>> assetStoreMap;
        Map<?, ?> tagMap;
        Map<?, ?> clientTagMap;

        try {
            // Get the fields we need to access
            Field storeMapField = AssetRegistry.class.getDeclaredField("storeMap");
            Field tagMapField = AssetRegistry.class.getDeclaredField("TAG_MAP");
            Field clientTagMapField = AssetRegistry.class.getDeclaredField("CLIENT_TAG_MAP");

            // Set the fields to accessible
            storeMapField.setAccessible(true);
            tagMapField.setAccessible(true);
            clientTagMapField.setAccessible(true);

            // Get the values from the fields
            assetStoreMap = (Map<Object, AssetStore<?, ?, ?>>) storeMapField.get(AssetRegistry.class);
            tagMap = (Map<?, ?>) tagMapField.get(AssetRegistry.class);
            clientTagMap = (Map<?, ?>) clientTagMapField.get(AssetRegistry.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.error("Error getting the asset store map: %t", e);
            return;
        }

        // Get the root path of the asset pack
        var root = assetPack.getRoot();

        // Unregister existing assets
        var modAssets = getModAssetList(root, pluginId);

        // If the list is not empty, unregister the assets
        if (modAssets != null && !modAssets.isEmpty()) {
            logger.debug("assetStoreMap: %s", Arrays.toString(assetStoreMap.values().stream().map(value -> value.getPath()).toArray()));

            for (Path asset : modAssets) {
                // Remove the asset from the asset store map
                assetStoreMap.values().removeIf(value -> {
                    var assetAsString = asset.toString();

                    // If the asset path is the same as the asset path we are trying to remove
                    if (value.getPath().equals(assetAsString)) {
                        logger.debug("Unregistering asset %s", assetAsString);
                        return true;
                    }

                    return false;
                });
            }
        }

        // Load the assets
        // We need to send the new assets to the clients before we load them
        // `event` can be null, and Hytale will handle it correctly
        AssetRegistryLoader.loadAssets(null, assetPack);

        // Send the assets to the clients
        sendAssetsToClients(modAssets, pluginId);
    }

    /**
     * Get the assets from the path
     * @param assetPath The path of the asset
     * @param pluginId The plugin identifier
     * @return The list of assets, or null if no assets were found
     */
    private static List<Path> getModAssetList(Path assetPath, PluginIdentifier pluginId) {
        // List of paths to check
        List<String> pathesToCheckList = new ArrayList<>();

        // Get the immutable asset store map
        var immutableAssetStoreMap = AssetRegistry.getStoreMap();

        // Iterate over the immutable asset store map
        for (Map.Entry<?, AssetStore<?, ?, ?>> entry : immutableAssetStoreMap.entrySet()) {
            pathesToCheckList.add(entry.getValue().getPath());
        }

        // Add the "Server" directory to the list
        pathesToCheckList.add("Server");

        // Add the "Common" directory to the list
        pathesToCheckList.add("Common");

        logger.debug("The following paths will be checked for assets: %s", String.join(", ", pathesToCheckList));

        // Convert the list of paths to check to a list of absolute paths
        List<Path> absolutePathesToCheckList = pathesToCheckList.stream().map(path -> assetPath.resolve(path)).toList();

        try {
            // List all files including subdirectories
            List<Path> files = Files.walk(assetPath).toList();

            // List of assets to send
            List<Path> modAssetList = new ArrayList<>();

            // Iterate over the files
            for (Path file : files) {
                // If the file is a directory, continue
                if (Files.isDirectory(file, new LinkOption[0])) {
                    continue;
                }

                // If the path is not in the list of paths to check, continue
                if (!absolutePathesToCheckList.stream().anyMatch(path -> file.toAbsolutePath().startsWith(path))) {
                    continue;
                }

                // Add the file to the list of files to send
                modAssetList.add(file);
            }

            return modAssetList;
        } catch (IOException e) {
            logger.error("Error listing files: %t", e);
        }
        
        return null;
    }

    /**
     * Send the assets to the clients
     * @param assetPath The path of the asset
     * @param pluginId The plugin identifier
     */
    private static void sendAssetsToClients(List<Path> modAssetList, PluginIdentifier pluginId) {
        logger.info("Sending %d assets to the clients", modAssetList.size());

        // Iterate over the files to send
        for (Path file : modAssetList) {
            // Send the asset to the clients
            sendAssetToClients(file, pluginId);
        }

        logger.info("Assets sent to the clients");
    }

    /**
     * Send an asset to the clients
     * This abuses a packet that is used to send assets to the clients out of the SETUP phase
     * Idk why this is there, but we will use it until Hytale removes it
     * @param path The path of the asset
     * @param pluginId The plugin identifier
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

        // Normalize the path
        var normalizedPath = path.toString().replace("^/", "");

        // First, prepare the asset
        // Remove the leading "/" from the path
        ModAsset modAsset = new ModAsset(normalizedPath, assetBytes);

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
                        Message.raw("Receiving asset: " + normalizedPath + " from mod " + pluginId.toString()).color(Colors.LIGHT_GRAY)
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
                        Message.raw("Received asset: " + normalizedPath + " from mod " + pluginId.toString()).color(Colors.LIGHT_GRAY)
                    );
                }
            }, world);
        }
    }
}
