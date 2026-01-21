package com.machina.mdevtools.tasks;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.tasks.modreload.FakeModulePlugin;
import com.machina.mdevtools.tasks.modreload.ModReloadDependency;
import com.machina.mdevtools.tasks.modreload.ModReloadResult;
import com.machina.mdevtools.tasks.modreload.ModReloadState;
import com.machina.mdevtools.tasks.modreload.PendingMod;
import com.machina.mdevtools.util.HybridWatcher;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.util.ModJarUtils;

public class ModReloadTask extends Thread {
    /**
     * List of mod identifiers that are being reloaded
     */
    public static final Map<PluginIdentifier, ModReloadState> RELOADING_MODS = new HashMap<>();

    /**
     * Maximum number of retry attempts for incomplete JARs
     */
    private static final int MAX_RETRIES = 5;

    /**
     * Whether the task is running
     */
    private boolean running = true;

    /**
     * Map of pending plugins waiting to be reloaded
     * Key: Path to the mod file (for fast lookup)
     * Value: PendingMod record with path and detection timestamp
     */
    private Map<Path, PendingMod> pendingPlugins = new HashMap<>();
    
    /**
     * Map tracking retry counts for each mod file
     * Key: Normalized path to the mod file
     * Value: Number of retry attempts made
     */
    private Map<Path, Integer> retryCounts = new HashMap<>();
    
    /**
     * Set of paths currently being processed to prevent duplicate reloads
     */
    private Set<Path> processingPaths = new HashSet<>();

    /**
     * The logger for the task
     */
    private final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadTask");

    /**
     * Delay in milliseconds before reloading a mod after it's detected
     */
    private long reloadDelayMs;

    /**
     * Time in milliseconds to wait checking if file size is stable before reloading
     */
    private long fileStabilityCheckMs;
    
    /**
     * Whether configuration has been initialized
     */
    private boolean configInitialized = false;

    /**
     * Check if a mod is being reloaded
     * @param modId The mod identifier
     * @return True if the mod is being reloaded, false otherwise
     */
    public static boolean isReloading(PluginIdentifier modId) {
        return RELOADING_MODS.containsKey(modId);
    }

    /**
     * Set the state of the dependencies of a mod
     * @param modId The mod identifier
     * @param state The state to set
     */
    public static void setDependenciesState(PluginIdentifier modId, PluginState state) {
        ModReloadState reloadState = RELOADING_MODS.get(modId);

        // Ignore if the mod is not being reloaded
        if (reloadState == null) {
            return;
        }

        // Iterate over the dependencies and set the state
        for (ModReloadDependency dependency : reloadState.dependencies()) {
            Main.INSTANCE.logger.debug("Setting dependency %s state to %s", dependency.dependencyName, state);

            // Ignore if the dependency is not found
            if (dependency.pluginRef == null) {
                Main.INSTANCE.logger.warn("Dependency %s not found, skipping", dependency.dependencyName);
                continue;
            }

            // Set the state of the dependency
            try {
                Field stateField = PluginBase.class.getDeclaredField("state");
                stateField.setAccessible(true);
                stateField.set(dependency.pluginRef, state);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                Main.INSTANCE.logger.error("Failed to set dependency %s state to %s: %t", dependency.dependencyName, state, e);
            }
        }
    }

    public ModReloadTask() {
        super("Mod Reload Task");
        
        // Load configuration values (will be set in first run() iteration if needed)
        this.reloadDelayMs = 1000; // Default value, will be overridden in run()
        this.fileStabilityCheckMs = 500; // Default value, will be overridden in run()
    }
    
    /**
     * Initialize configuration values from Main.INSTANCE.config
     */
    private void initConfig() {
        // Initialize the configuration if it hasn't been initialized yet
        if (!configInitialized && Main.INSTANCE != null && Main.INSTANCE.config != null) {
            try {
                // Get the reload delay duration
                reloadDelayMs = Main.INSTANCE.config.getLong("mods.reloadDelayMs", 1000);

                // Get the file stability check duration
                fileStabilityCheckMs = Main.INSTANCE.config.getLong("mods.fileStabilityCheckMs", 500);

                // Set the configuration initialized flag
                configInitialized = true;
                
                logger.debug(
                    "ModReloadTask config loaded: reloadDelayMs=%d, fileStabilityCheckMs=%d", 
                    reloadDelayMs,
                    fileStabilityCheckMs
                );
            } catch (Exception e) {
                logger.warn("Failed to load config: %t", e);
            }
        }
    }

    @Override
    public void run() {
        initConfig();

        // Get the pathes to watch
        List<Path> modsPath = List.of(
            Path.of("mods"),
            Path.of("builtin"),
            Path.of("earlyplugins")
        );

        // Add the mods option
        modsPath.addAll(Options.MODS_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the early plugin directories
        modsPath.addAll(Options.EARLY_PLUGIN_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the configuration directories
        modsPath.addAll(Main.INSTANCE.config.getList("mods.additionalDirectories", List.of()).stream().map(Object::toString).map(Path::of).toList());

        // Deduplicate the list and make it unmodifiable
        modsPath = modsPath.stream().distinct().toList();
        final List<Path> finalModsPath = List.copyOf(modsPath);

        // Create the hybrid watcher
        HybridWatcher hybridWatcher = new HybridWatcher(finalModsPath, Duration.ofMillis(300));

        while (running && !Thread.currentThread().isInterrupted()) {
            initConfig();

            List<HybridWatcher.Entry> entries = hybridWatcher.poll();
            Map<Path, HybridWatcher.Entry> uniqueEntries = deduplicateEntries(entries);
            processNewEntries(uniqueEntries);

            long now = System.currentTimeMillis();
            List<PendingMod> readyMods = findReadyMods(now);
            processReadyMods(readyMods);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Deduplicate entries by path, keeping only the most recent event per path
     * @param entries The list of entries to deduplicate
     * @return Map of unique entries by normalized path
     */
    private Map<Path, HybridWatcher.Entry> deduplicateEntries(List<HybridWatcher.Entry> entries) {
        Map<Path, HybridWatcher.Entry> uniqueEntries = new HashMap<>();
        for (HybridWatcher.Entry entry : entries) {
            Path normalizedPath = normalizePath(entry.path());
            uniqueEntries.put(normalizedPath, new HybridWatcher.Entry(normalizedPath, entry.type()));
        }
        return uniqueEntries;
    }

    /**
     * Process new file system entries and add them to pending list
     * @param uniqueEntries Map of unique entries by normalized path
     */
    private void processNewEntries(Map<Path, HybridWatcher.Entry> uniqueEntries) {
        long now = System.currentTimeMillis();

        for (HybridWatcher.Entry entry : uniqueEntries.values()) {
            Path normalizedPath = normalizePath(entry.path());

            if (!isModFile(normalizedPath)) {
                logger.debug("File %s is not a mod, skipping", normalizedPath.getFileName().toString());
                continue;
            }

            boolean isDeleted = entry.type() == HybridWatcher.EventType.DELETED;
            Path existingKey = findExistingPendingKey(normalizedPath);

            // If the mod is not in the pending list
            if (existingKey == null) {
                // Add the mod to the pending list
                pendingPlugins.put(normalizedPath, new PendingMod(normalizedPath, now, 0, isDeleted));

                // Remove the mod from the retry counts
                retryCounts.remove(normalizedPath);

                logger.info(
                    "Added mod %s to pending list, waiting for delay and stability check", 
                    normalizedPath.getFileName().toString()
                );
            } else {
                // Get the current retry count
                int currentRetryCount = retryCounts.getOrDefault(normalizedPath, 0);

                // Remove the mod from the pending list and add it back with the new retry count
                pendingPlugins.remove(existingKey);
                pendingPlugins.put(normalizedPath, new PendingMod(normalizedPath, now, currentRetryCount, isDeleted));

                logger.debug(
                    "Mod %s still being written, resetting wait timer", 
                    normalizedPath.getFileName().toString()
                );
            }
        }
    }

    /**
     * Find mods that are ready to be reloaded
     * @param now Current timestamp
     * @return List of ready mods with their event types
     */
    private List<PendingMod> findReadyMods(long now) {
        List<PendingMod> readyMods = new ArrayList<>();

        // Iterate over the pending mods
        for (PendingMod pendingMod : new ArrayList<>(pendingPlugins.values())) {
            Path path = pendingMod.path();

            // Ignore if the mod is not in the pending list
            if (!pendingPlugins.containsKey(path)) {
                continue;
            }

            // Get the time since the mod was detected
            long timeSinceDetection = now - pendingMod.detectedAt();

            // Ignore if the mod is not ready yet
            if (timeSinceDetection < reloadDelayMs) {
                logger.trace(
                    "Mod %s not ready yet: %d ms since detection (need %d ms)", 
                    path.getFileName().toString(), timeSinceDetection, reloadDelayMs
                );

                continue;
            }

            // Ignore if the mod is not stable yet
            if (!pendingMod.isDeleted() && !isFileStable(path)) {
                logger.debug(
                    "Mod %s size is still changing, waiting for stability", 
                    path.getFileName().toString()
                );

                continue;
            }

            // Remove the mod from the pending list and add it to the ready list
            PendingMod removedPending = pendingPlugins.remove(path);


            if (removedPending != null) {
                // Add the mod to the retry counts and the ready list
                retryCounts.put(path, removedPending.retryCount());
                readyMods.add(removedPending);

                logger.debug(
                    "Mod %s is ready to reload, added to ready list (retry count: %d)", 
                    path.getFileName().toString(), removedPending.retryCount()
                );
            }
        }

        return readyMods;
    }

    /**
     * Process ready mods by reloading or handling deletion
     * @param readyMods List of mods ready to be processed
     */
    private void processReadyMods(List<PendingMod> readyMods) {
        // Iterate over the ready mods
        for (PendingMod pendingMod : readyMods) {
            Path normalizedPath = normalizePath(pendingMod.path());

            // Check if the mod is already being processed
            synchronized (processingPaths) {
                if (processingPaths.contains(normalizedPath)) {
                    logger.debug(
                        "Mod %s is already being processed, skipping duplicate", 
                        normalizedPath.getFileName().toString()
                    );

                    continue;
                }

                // Add the mod to the processing paths
                processingPaths.add(normalizedPath);
            }

            // Get the event type
            HybridWatcher.EventType eventType = pendingMod.isDeleted() 
                ? HybridWatcher.EventType.DELETED 
                : HybridWatcher.EventType.MODIFIED;

            // Disable URL connection caching
            CacheState cacheState = disableUrlCaching();

            // Initialize the result
            ModReloadResult result = null;

            try {
                // On the mod file updated
                result = onModFileUpdated(normalizedPath, eventType);
            } catch (Throwable e) {
                logger.error("Exception while processing mod %s: %t", normalizedPath.getFileName().toString(), e);
                result = ModReloadResult.error(e.getMessage());
            } finally {
                synchronized (processingPaths) {
                    processingPaths.remove(normalizedPath);
                }

                // Handle the reload result and restore URL connection caching
                handleReloadResult(normalizedPath, result, pendingMod);
                restoreUrlCaching(cacheState);
            }
        }
    }

    /**
     * Handle the result of a reload operation
     * @param normalizedPath The normalized path of the mod
     * @param result The reload result
     * @param pendingMod The pending mod that was processed
     */
    private void handleReloadResult(Path normalizedPath, ModReloadResult result, PendingMod pendingMod) {
        // Ignore if the result is null
        if (result == null) {
            return;
        }

        // If the result should retry
        if (result.shouldRetry()) {
            int currentRetryCount = retryCounts.getOrDefault(normalizedPath, 0);
            int newRetryCount = currentRetryCount + 1;

            // If the retry count is greater than the maximum retries
            if (newRetryCount > MAX_RETRIES) {
                retryCounts.remove(normalizedPath);
                logger.warn(
                    "Mod %s exceeded maximum retry count (%d), giving up. Reason: %s",
                    normalizedPath.getFileName().toString(),
                    MAX_RETRIES,
                    result.message() != null ? result.message() : "unknown reason"
                );
            } else {
                // Add the mod back to the pending list with the new retry count
                retryCounts.put(normalizedPath, newRetryCount);

                // Get the current timestamp
                long retryTimestamp = System.currentTimeMillis();

                // Add the mod back to the pending list with the new retry count
                pendingPlugins.put(normalizedPath, new PendingMod(normalizedPath, retryTimestamp, newRetryCount, false));
    
                logger.debug(
                    "Mod %s added back to pending list for retry %d/%d after delay: %s", 
                    normalizedPath.getFileName().toString(),
                    newRetryCount,
                    MAX_RETRIES,
                    result.message() != null ? result.message() : "unknown reason"
                );
            }
        } else
        // If the result is successful
        if (result.isSuccess()) {
            retryCounts.remove(normalizedPath);
        }
    }

    /**
     * Record to hold URL connection cache state
     */
    private record CacheState(boolean fileUseCaches, boolean jarUseCaches) {}

    /**
     * Disable URL connection caching for file and jar protocols
     * @return The previous cache state
     */
    private CacheState disableUrlCaching() {
        boolean fileUseCaches = URLConnection.getDefaultUseCaches("file");
        boolean jarUseCaches = URLConnection.getDefaultUseCaches("jar");

        if (fileUseCaches) {
            URLConnection.setDefaultUseCaches("file", false);
        }
        if (jarUseCaches) {
            URLConnection.setDefaultUseCaches("jar", false);
        }

        return new CacheState(fileUseCaches, jarUseCaches);
    }

    /**
     * Restore URL connection caching to previous state
     * @param cacheState The previous cache state
     */
    private void restoreUrlCaching(CacheState cacheState) {
        if (cacheState.fileUseCaches()) {
            URLConnection.setDefaultUseCaches("file", true);
        }

        if (cacheState.jarUseCaches()) {
            URLConnection.setDefaultUseCaches("jar", true);
        }
    }

    /**
     * Normalize a path for consistent comparison
     * @param path The path to normalize
     * @return The normalized absolute path
     */
    private Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Check if a file is a mod file (.zip or .jar)
     * @param path The path to check
     * @return True if the file is a mod, false otherwise
     */
    private boolean isModFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".zip") || fileName.endsWith(".jar");
    }

    /**
     * Find an existing pending key that matches the normalized path
     * @param normalizedPath The normalized path to find
     * @return The existing key if found, null otherwise
     */
    private Path findExistingPendingKey(Path normalizedPath) {
        for (Path key : pendingPlugins.keySet()) {
            if (normalizePath(key).equals(normalizedPath)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Check if a file is stable (size hasn't changed in the configured time)
     * @param filePath The path to the file
     * @return True if the file is stable, false otherwise
     */
    private boolean isFileStable(Path filePath) {
        if (!Files.exists(filePath)) {
            return false;
        }
        
        try {
            // Get initial file size
            long initialSize = Files.size(filePath);
            
            // Wait for the stability check duration
            Thread.sleep(fileStabilityCheckMs);
            
            // Check if file size changed
            if (!Files.exists(filePath)) {
                return false;
            }
            
            long finalSize = Files.size(filePath);
            boolean isStable = initialSize == finalSize;
            
            if (!isStable) {
                logger.debug(
                    "File %s size changed: %d -> %d bytes", 
                    filePath.getFileName().toString(),
                    initialSize,
                    finalSize
                );
            }
            
            return isStable;
        } catch (IOException e) {
            logger.warn("Error checking file stability for %s: %t", filePath.getFileName().toString(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Handle mod file update or deletion
     * @param filePath The path to the mod file
     * @param eventType The event type (MODIFIED, CREATED, or DELETED)
     * @return The result of the operation
     * @throws Exception if an error occurs during the operation
     */
    private synchronized ModReloadResult onModFileUpdated(Path filePath, HybridWatcher.EventType eventType) throws Exception {
        String fileName = filePath.getFileName().toString();

        // If the mod file is deleted
        if (eventType == HybridWatcher.EventType.DELETED) {
            return onModFileDeleted(filePath, fileName);
        }

        logger.info("Mod file %s updated", fileName);
        PluginIdentifier modId = null;

        try {
            // Get the mod manifest
            ModJarUtils.ModManifest manifest = ModJarUtils.getModManifest(filePath);

            // Ignore if the manifest is missing or invalid
            if (manifest == null) {
                String message = "manifest.json is missing or invalid, may be incomplete";
                logger.warn("Mod %s %s - will retry after delay", fileName, message);
                return ModReloadResult.retryNeeded(message);
            }

            // Get the plugin name and mod identifier
            String pluginName = manifest.getFullPluginName();
            modId = PluginIdentifier.fromString(pluginName);

            // Create the reload state and add it to the reloading mods map
            ModReloadState reloadState = new ModReloadState();
            RELOADING_MODS.put(modId, reloadState);

            // Get the plugin map and setup dependencies
            Map<PluginIdentifier, JavaPlugin> hytalePluginList = getPluginMap();
            setupDependencies(manifest, reloadState, hytalePluginList, pluginName);
            setDependenciesState(modId, PluginState.SETUP);

            // Get the existing plugin
            PluginIdentifier pluginId = PluginIdentifier.fromString(pluginName);
            JavaPlugin existingPlugin = hytalePluginList.get(pluginId);

            // Ignore if the plugin is not found
            if (existingPlugin == null) {
                if (!loadPlugin(pluginId, pluginName)) {
                    return ModReloadResult.error("Failed to load mod");
                }
            } else {
                if (!reloadPlugin(pluginId, pluginName)) {
                    return ModReloadResult.error("Failed to reload mod");
                }
            }

            restoreDependencies(reloadState, hytalePluginList, pluginName);

            logger.info("Mod %s has been reloaded", pluginName);
            return ModReloadResult.success();
        } catch (Exception e) {
            throw e;
        } finally {
            if (modId != null) {
                RELOADING_MODS.remove(modId);
            }
        }
    }

    /**
     * Handle mod file deletion by disabling the plugin
     * @param filePath The path to the deleted mod file
     * @param fileName The file name for logging
     * @return The result of the deletion operation
     */
    private ModReloadResult onModFileDeleted(Path filePath, String fileName) {
        // If doesn't support deletion, return success
        if (!Main.INSTANCE.config.getBoolean("mods.unloadWhenDeleted", false)) {
            logger.warn("Mod %s was deleted, but unloading is not supported, skipping", fileName);
            return ModReloadResult.success();
        }

        logger.info("Mod file %s was deleted, attempting to unload plugin", fileName);

        PluginIdentifier modId = null;

        try {
            // Get the plugin map and find the plugin identifier
            Map<PluginIdentifier, JavaPlugin> hytalePluginList = getPluginMap();
            modId = findPluginIdByPath(filePath, hytalePluginList);

            // Ignore if the plugin is not found
            if (modId == null) {
                logger.debug("Mod %s was not loaded, nothing to unload", fileName);
                return ModReloadResult.success();
            }

            // Get the plugin
            JavaPlugin plugin = hytalePluginList.get(modId);

            // Ignore if the plugin is not found
            if (plugin == null) {
                logger.warn("Plugin %s seems not to be loaded, nothing to unload", modId);
                return ModReloadResult.success();
            }

            // Unload the plugin
            PluginManager.get().unload(modId);

            logger.info("Mod %s has been unloaded", fileName);
            return ModReloadResult.success();
        } catch (Exception e) {
            logger.error("Failed to unload mod %s (%s): %t", fileName, modId, e);
            return ModReloadResult.error("Failed to unload mod: " + e.getMessage());
        }
    }

    /**
     * Find plugin identifier by file path by checking all loaded plugins
     * @param filePath The file path to search for
     * @param hytalePluginList The plugin map
     * @return The plugin identifier if found, null otherwise
     */
    private PluginIdentifier findPluginIdByPath(Path filePath, Map<PluginIdentifier, JavaPlugin> hytalePluginList) {
        Path normalizedTargetPath = normalizePath(filePath);

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

                if (normalizePath(pluginPath).equals(normalizedTargetPath)) {
                    return entry.getKey();
                }
            } catch (Exception e) {
                logger.debug("Error checking plugin path for %s: %t", entry.getKey(), e);
            }
        }
        return null;
    }

    /**
     * Get the plugin map using reflection
     * @return The plugin map
     * @throws Exception if reflection fails
     */
    private Map<PluginIdentifier, JavaPlugin> getPluginMap() throws Exception {
        Field pluginsField = PluginManager.class.getDeclaredField("plugins");
        pluginsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<PluginIdentifier, JavaPlugin> pluginMap = (Map<PluginIdentifier, JavaPlugin>) pluginsField.get(PluginManager.get());
        return pluginMap;
    }

    /**
     * Setup dependencies for a mod reload
     * @param manifest The mod manifest
     * @param reloadState The reload state to populate
     * @param hytalePluginList The plugin map
     * @param pluginName The plugin name for logging
     */
    private void setupDependencies(
        ModJarUtils.ModManifest manifest,
        ModReloadState reloadState,
        Map<PluginIdentifier, JavaPlugin> hytalePluginList,
        String pluginName
    ) throws Exception {
        // Get the dependencies names list
        List<String> dependenciesNamesList = manifest.getDependenciesNamesList();

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
    private void restoreDependencies(
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

    /**
     * Load a plugin
     * @param pluginId The plugin identifier
     * @param pluginName The plugin name for logging
     * @return True if successful, false otherwise
     */
    private boolean loadPlugin(PluginIdentifier pluginId, String pluginName) {
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
    private boolean reloadPlugin(PluginIdentifier pluginId, String pluginName) {
        // Reload the plugin
        if (PluginManager.get().reload(pluginId)) {
            logger.info("Mod %s has been reloaded", pluginName);
            return true;
        } else {
            logger.error("Failed to reload mod %s", pluginName);
            return false;
        }
    }
}