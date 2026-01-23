package com.machina.mdevtools.tasks;

import java.lang.reflect.Field;
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
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.tasks.modreload.ModReloadDependency;
import com.machina.mdevtools.tasks.modreload.ModReloadDependencyManager;
import com.machina.mdevtools.tasks.modreload.ModReloadExcludeChecker;
import com.machina.mdevtools.tasks.modreload.ModReloadFileHandler;
import com.machina.mdevtools.tasks.modreload.ModReloadPathUtils;
import com.machina.mdevtools.tasks.modreload.ModReloadPluginManager;
import com.machina.mdevtools.tasks.modreload.ModReloadResult;
import com.machina.mdevtools.tasks.modreload.ModReloadState;
import com.machina.mdevtools.tasks.modreload.ModReloadUrlCacheManager;
import com.machina.mdevtools.tasks.modreload.PendingMod;
import com.machina.mdevtools.util.HybridWatcher;
import com.machina.shared.factory.ModLogger;

public class ModReloadTask extends Thread {
    /**
     * List of mod identifiers that are being reloaded
     */
    public static final Map<PluginIdentifier, ModReloadState> RELOADING_MODS = new HashMap<>();

    /**
     * Reference to the current instance of the task
     */
    private static volatile ModReloadTask instance;

    /**
     * Maximum number of retry attempts for incomplete JARs
     */
    private static final int MAX_RETRIES = 5;

    /**
     * The logger for the task (lazy initialized)
     */
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "ModReloadTask");

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
     * Plugin manager for reload operations
     */
    private final ModReloadPluginManager pluginManager = new ModReloadPluginManager(logger);

    /**
     * Dependency manager for reload operations
     */
    private final ModReloadDependencyManager dependencyManager = new ModReloadDependencyManager(logger);

    /**
     * Exclude checker for mod reload
     */
    private final ModReloadExcludeChecker excludeChecker = new ModReloadExcludeChecker();

    /**
     * File handler for mod file operations
     */
    private final ModReloadFileHandler fileHandler = new ModReloadFileHandler(
        RELOADING_MODS,
        logger,
        pluginManager,
        dependencyManager,
        excludeChecker
    );

    /**
     * Add a plugin to the pending list
     * @param pluginId The plugin identifier
     */
    public static void addPendingPlugin(PluginIdentifier pluginId) {
        // Add to the reloading mods map
        RELOADING_MODS.put(pluginId, new ModReloadState());

        // Get the current instance
        ModReloadTask currentInstance = instance;
        if (currentInstance == null) {
            if (Main.INSTANCE != null) {
                logger.warn("ModReloadTask instance not available, plugin %s added to RELOADING_MODS but not to pending list", pluginId);
            }
            return;
        }

        try {
            // Get the plugin map to find the plugin file
            Map<PluginIdentifier, JavaPlugin> hytalePluginList = currentInstance.pluginManager.getPluginMap();
            JavaPlugin plugin = hytalePluginList.get(pluginId);

            // If the plugin is not found, log a warning
            if (plugin == null) {
                logger.warn("Plugin %s not found in plugin map, cannot add to pending list", pluginId);
                return;
            }

            // Get the plugin file path
            Path pluginPath = plugin.getFile();
            if (pluginPath == null) {
                logger.warn("Plugin %s has no file path, cannot add to pending list", pluginId);
                return;
            }

            // Normalize the path
            Path normalizedPath = ModReloadPathUtils.normalizePath(pluginPath);

            // Check if the file exists and is a mod file
            if (!java.nio.file.Files.exists(normalizedPath)) {
                logger.warn("Plugin file %s does not exist, cannot add to pending list", normalizedPath);
                return;
            }

            if (!ModReloadPathUtils.isModFile(normalizedPath)) {
                logger.warn("Plugin file %s is not a mod file, cannot add to pending list", normalizedPath);
                return;
            }

            long now = System.currentTimeMillis();

            synchronized (currentInstance.pendingPlugins) {
                // Check if already in pending list (inside synchronized block to avoid race conditions)
                Path existingKey = currentInstance.findExistingPendingKey(normalizedPath);
                
                if (existingKey != null) {
                    // Update the existing entry
                    int currentRetryCount = currentInstance.retryCounts.getOrDefault(normalizedPath, 0);
                    currentInstance.pendingPlugins.remove(existingKey);
                    currentInstance.pendingPlugins.put(normalizedPath, new PendingMod(normalizedPath, now, currentRetryCount, false));
                    logger.debug("Plugin %s already in pending list, resetting wait timer", pluginId);
                } else {
                    // Add new entry
                    currentInstance.pendingPlugins.put(normalizedPath, new PendingMod(normalizedPath, now, 0, false));
                    currentInstance.retryCounts.remove(normalizedPath);
                    logger.info("Plugin %s added to pending list for reload", pluginId);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to add plugin %s to pending list: %t", pluginId, e);
        }
    }

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
            logger.debug("Setting dependency %s state to %s", dependency.dependencyName, state);

            // Ignore if the dependency is not found
            if (dependency.pluginRef == null) {
                logger.warn("Dependency %s not found, skipping", dependency.dependencyName);
                continue;
            }

            // Set the state of the dependency
            try {
                Field stateField = PluginBase.class.getDeclaredField("state");
                stateField.setAccessible(true);
                stateField.set(dependency.pluginRef, state);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.error("Failed to set dependency %s state to %s: %t", dependency.dependencyName, state, e);
            }
        }
    }

    public ModReloadTask() {
        super("Mod Reload Task");
        
        // Register this instance
        instance = this;
        
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

        // Workaround for java.lang.UnsupportedOperationException (ImmutableCollections)
        // List.of() returns an immutable list, so we must use a mutable list for addAll()
        List<Path> mutableModsPath = new ArrayList<>(modsPath);

        // Add the mods option
        mutableModsPath.addAll(Options.MODS_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the early plugin directories
        mutableModsPath.addAll(Options.EARLY_PLUGIN_DIRECTORIES.options().stream().map(Path::of).toList());

        // Add the configuration directories
        mutableModsPath.addAll(Main.INSTANCE.config.getList("mods.reload.additionalDirectories", List.of()).stream()
            .map(Object::toString)
            .map(Path::of)
            .toList());

        modsPath = mutableModsPath;

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
            Path normalizedPath = ModReloadPathUtils.normalizePath(entry.path());
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
            Path normalizedPath = ModReloadPathUtils.normalizePath(entry.path());

            if (!ModReloadPathUtils.isModFile(normalizedPath)) {
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
            if (!pendingMod.isDeleted() && !ModReloadPathUtils.isFileStable(path, fileStabilityCheckMs, logger)) {
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
            Path normalizedPath = ModReloadPathUtils.normalizePath(pendingMod.path());

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
            ModReloadUrlCacheManager.CacheState cacheState = ModReloadUrlCacheManager.disableUrlCaching();

            // Initialize the result
            ModReloadResult result = null;

            try {
                // On the mod file updated
                result = fileHandler.onModFileUpdated(normalizedPath, eventType);
            } catch (Throwable e) {
                logger.error("Exception while processing mod %s: %t", normalizedPath.getFileName().toString(), e);
                result = ModReloadResult.error(e.getMessage());
            } finally {
                synchronized (processingPaths) {
                    processingPaths.remove(normalizedPath);
                }

                // Handle the reload result and restore URL connection caching
                handleReloadResult(normalizedPath, result, pendingMod);
                ModReloadUrlCacheManager.restoreUrlCaching(cacheState);
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
     * Find an existing pending key that matches the normalized path
     * @param normalizedPath The normalized path to find
     * @return The existing key if found, null otherwise
     */
    private Path findExistingPendingKey(Path normalizedPath) {
        for (Path key : pendingPlugins.keySet()) {
            if (ModReloadPathUtils.normalizePath(key).equals(normalizedPath)) {
                return key;
            }
        }
        return null;
    }
}