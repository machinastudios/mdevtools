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

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetRegistryLoader;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.Colors;
import com.machina.shared.util.PlayerUtil;

public class ModReloadAssetReload {
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadAssetReload");

    /**
     * Reload and resend all assets to clients.
     * @param pluginId The plugin identifier
     */
    public static void reloadAssetPack(PluginIdentifier pluginId) {
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

        // Declare variables that will be used later
        Map<Object, AssetStore<?, ?, ?>> assetStoreMap;

        try {
            // Get the fields we need to access
            Field storeMapField = AssetRegistry.class.getDeclaredField("storeMap");

            // Set the fields to accessible
            storeMapField.setAccessible(true);

            // Get the values from the fields
            assetStoreMap = (Map<Object, AssetStore<?, ?, ?>>) storeMapField.get(AssetRegistry.class);
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

        // Add the "Cosmetics" directory to the list
        pathesToCheckList.add("Cosmetics");

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
     * @param modAssetList The list of assets to send
     * @param pluginId The plugin identifier
     */
    private static void sendAssetsToClients(List<Path> modAssetList, PluginIdentifier pluginId) {
        logger.info("Sending %d assets to the clients", modAssetList.size());

        // Separate Common assets from Server assets
        // Common assets include: Common/, Cosmetics/
        // Server assets include: Server/
        List<Path> commonAssets = new ArrayList<>();
        List<Path> serverAssets = new ArrayList<>();
        
        String separator = Path.of("").getFileSystem().getSeparator();
        for (Path file : modAssetList) {
            String pathStr = file.toString();
            if (pathStr.contains("Common" + separator) || pathStr.contains("/Common/") ||
                pathStr.contains("Cosmetics" + separator) || pathStr.contains("/Cosmetics/")) {
                commonAssets.add(file);
            } else {
                serverAssets.add(file);
            }
        }

        logger.info(
            "Found %d Common assets (Common + Cosmetics) and %d Server assets", 
            commonAssets.size(), serverAssets.size()
        );

        if (!commonAssets.isEmpty()) {
            logger.info(
                "Registering %d Common assets (Common + Cosmetics) using CommonAssetModule", 
                commonAssets.size()
            );

            registerCommonAssets(commonAssets, pluginId);
        }

        if (!modAssetList.isEmpty()) {
            logger.info("Sending RequestCommonAssetsRebuild to all clients");

            // Use the `broadcastPacketNoCache` method to send the packet to all clients
            Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
            
            logger.info("Sent RequestCommonAssetsRebuild to all clients");

        }

        // Notify the players that the common assets have been reloaded
        if (!commonAssets.isEmpty()) {
            for (PlayerRef player : Universe.get().getPlayers()) {
                if (PlayerUtil.isOp(player)) {
                    PlayerUtil.sendPluginMessage(
                        player,
                        Main.INSTANCE,
                        Message.raw("Common assets reloaded.").color(Colors.LIGHT_GRAY)
                    );
                }
            }
        }
        
        logger.info("Assets sent to the clients");
    }

    /**
     * Register Common assets using the official Hytale system.
     * This is the correct way to reload CommonAssets (Models, Textures, Particles, Cosmetics, etc.)
     * 
     * CommonAssets are files from Common/ and Cosmetics/ directories.
     * 
     * IMPORTANT: This method REMOVES the old asset before adding the new one to force Hytale
     * to treat it as a new asset, even if the hash didn't change. This ensures the asset is
     * always sent to clients and reloaded properly.
     * 
     * @param commonAssets List of Common asset paths (from Common/ and Cosmetics/)
     * @param pluginId The plugin identifier
     */
    private static void registerCommonAssets(List<Path> commonAssets, PluginIdentifier pluginId) {
        // Get the asset pack root once
        AssetModule assetModule = AssetModule.get();
        var assetPack = assetModule.getAssetPack(pluginId.toString());
        if (assetPack == null) {
            logger.error("Asset pack not found for plugin %s", pluginId.toString());
            return;
        }
        
        Path root = assetPack.getRoot();
        logger.debug("Asset pack root: %s", root.toAbsolutePath());
        
        for (Path assetPath : commonAssets) {
            try {
                // Read the asset bytes
                byte[] assetBytes = Files.readAllBytes(assetPath);
                
                // Calculate relative path from pack root
                // The path should start with Common/, Cosmetics/, etc.
                Path relativePath = root.relativize(assetPath);
                String normalizedPath = relativePath.toString().replace("\\", "/");
                
                // Debug: Log the paths being used
                logger.debug("Absolute path: %s", assetPath.toAbsolutePath());
                logger.debug("Root path: %s", root.toAbsolutePath());
                logger.debug("Relative path (raw): %s", normalizedPath);
                
                // FIX: Remove plugin identifier from path if present
                // Sometimes the path includes the plugin name like "com.machina:mauth/Common/..."
                // We need to strip that and keep only "Common/..." or "Cosmetics/..."
                String pluginPrefix = pluginId.toString() + "/";
                if (normalizedPath.startsWith(pluginPrefix)) {
                    normalizedPath = normalizedPath.substring(pluginPrefix.length());
                    logger.debug("Stripped plugin prefix, new path: %s", normalizedPath);
                }
                
                // Extract only the part starting from Common/, Cosmetics/, or Server/
                if (normalizedPath.contains("/Common/")) {
                    normalizedPath = "Common/" + normalizedPath.substring(normalizedPath.indexOf("/Common/") + 8);
                    logger.debug("Extracted Common path: %s", normalizedPath);
                } else if (normalizedPath.contains("/Cosmetics/")) {
                    normalizedPath = "Cosmetics/" + normalizedPath.substring(normalizedPath.indexOf("/Cosmetics/") + 11);
                    logger.debug("Extracted Cosmetics path: %s", normalizedPath);
                } else if (!normalizedPath.startsWith("Common/") && 
                           !normalizedPath.startsWith("Cosmetics/")) {
                    logger.warn("Asset path doesn't start with Common/ or Cosmetics/: %s", normalizedPath);
                    logger.warn("This might cause issues. Expected format: Common/UI/test.ui");
                }
                
                logger.info("Final asset path: %s", normalizedPath);
                
                // CRITICAL FIX: Remove the old asset first to force Hytale to treat it as new
                // If we don't do this, CommonAssetModule.addCommonAsset() will check if the hash
                // changed, and if it didn't, it won't send the asset to clients.
                // By removing first, we force it to always be treated as a "new" asset.
                var removed = CommonAssetRegistry.removeCommonAssetByName(pluginId.toString(), normalizedPath);
                if (removed != null) {
                    logger.debug("Removed old asset: %s", normalizedPath);
                } else {
                    logger.debug("No old asset to remove: %s (this is a new asset)", normalizedPath);
                }
                
                // Create ModAsset (which extends CommonAsset)
                ModAsset modAsset = new ModAsset(normalizedPath, assetBytes);
                
                // Register using the official Hytale method
                // This will:
                // - Add to CommonAssetRegistry
                // - Invalidate caches
                // - Send to clients automatically (because we removed the old one, it's always "new")
                // NOTE: sendAsset() is called with forceRebuild: false, so it WON'T send
                // RequestCommonAssetsRebuild automatically. We'll send it manually later.
                CommonAssetModule.get().addCommonAsset(pluginId.toString(), modAsset, true);
                
                logger.info("Registered Common asset: %s (hash: %s)", normalizedPath, modAsset.getHash());
            } catch (IOException e) {
                logger.error("Error reading Common asset %s: %t", assetPath, e);
            } catch (Exception e) {
                logger.error("Unexpected error registering asset %s: %t", assetPath, e);
            }
        }
    }
}
