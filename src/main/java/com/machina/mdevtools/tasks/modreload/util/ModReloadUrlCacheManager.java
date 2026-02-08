package com.machina.mdevtools.tasks.modreload.util;

import java.net.URLConnection;

/**
 * Utility class for managing URL connection caching during mod reload.
 */
public final class ModReloadUrlCacheManager {
    /**
     * Record to hold URL connection cache state
     */
    public record CacheState(boolean fileUseCaches, boolean jarUseCaches) {}

    /**
     * Disable URL connection caching for file and jar protocols
     * @return The previous cache state
     */
    public static CacheState disableUrlCaching() {
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
    public static void restoreUrlCaching(CacheState cacheState) {
        if (cacheState.fileUseCaches()) {
            URLConnection.setDefaultUseCaches("file", true);
        }

        if (cacheState.jarUseCaches()) {
            URLConnection.setDefaultUseCaches("jar", true);
        }
    }
}
