package com.machina.mdevtools.util;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.machina.mdevtools.Main;
import com.machina.shared.factory.ModLogger;

public class HybridWatcher {
    /**
     * The logger for the hybrid watcher for debugging purposes
     */
    private static final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "HybridWatcher");

    /**
     * The roots of the filesystem to watch
     */
    private final List<Path> roots;

    /**
     * The interval of the polling
     */
    private final Duration interval;

    /**
     * The last modified time of the files
     */
    private final Map<Path, Long> lastModified = new HashMap<>();

    /**
     * The watch service
     */
    private WatchService ws;

    /**
     * True if the watch service is working, false otherwise
     */
    private static Boolean wsWorking = null;

    /**
     * The last poll time
     */
    private long lastPoll = 0;

    public HybridWatcher(List<Path> roots, Duration interval) {
        logger.debug("Initializing HybridWatcher with %d root(s) and interval: %s", roots.size(), interval);
        this.roots = roots;
        this.interval = interval;

        // Iterate over the roots and log them
        for (Path root : roots) {
            logger.debug("Watching root: %s", root);
        }

        initSnapshot();
        initWatchService();
        logger.debug("HybridWatcher initialization completed");
    }

    /**
     * Initialize the snapshot of the filesystem
     */
    private void initSnapshot() {
        logger.debug("Initializing filesystem snapshot");
        int totalFiles = 0;

        for (Path root : roots) {
            // Skip if directory doesn't exist
            if (!Files.exists(root)) {
                logger.debug("Skipping snapshot for root %s: directory does not exist", root);
                continue;
            }
            
            try (var stream = Files.walk(root)) {
                int fileCount = (int) stream.filter(Files::isRegularFile)
                      .peek(f -> {
                          lastModified.put(f, getModTime(f));
                      })
                      .count();
                totalFiles += fileCount;
                logger.debug("Snapshot initialized for root %s: %d files tracked", root, fileCount);
            } catch (IOException e) {
                logger.warn("Failed to initialize snapshot for root %s: %s", root, e.getMessage());
            }
        }

        logger.debug("Filesystem snapshot completed: %d total files tracked", totalFiles);
    }

    /**
     * Get the modification time of a file
     * @param p The path of the file
     * @return The modification time
     */
    private long getModTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            logger.warn("Failed to get modification time for %s: %s", p, e.getMessage());
            return 0;
        }
    }

    /**
     * Initialize the watch service
     */
    private void initWatchService() {
        logger.debug("Initializing watch service");
        try {
            ws = FileSystems.getDefault().newWatchService();
            logger.debug("Watch service created successfully");

            int totalDirs = 0;

            // Iterate over the roots
            for (Path root : roots) {
                // Skip if directory doesn't exist
                if (!Files.exists(root)) {
                    logger.debug("Skipping watch service registration for root %s: directory does not exist", root);
                    continue;
                }
                
                try (var dirs = Files.walk(root)) {
                    // Count the number of directories
                    AtomicInteger dirCount = new AtomicInteger(0);

                    // Register the watch for the directories
                    dirs.filter(Files::isDirectory)
                        .forEach(dir -> {
                            try {
                                dir.register(ws,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_MODIFY,
                                        StandardWatchEventKinds.ENTRY_DELETE);
                                dirCount.incrementAndGet();

                                logger.debug("Registered watch for directory: %s", dir);
                            } catch (IOException e) {
                                logger.warn("Failed to register watch for directory %s: %t", dir, e);
                            }
                        });

                    totalDirs += dirCount.get();
                    logger.debug("Registered %d directories for root %s", dirCount.get(), root);
                } catch (IOException e) {
                    logger.warn("Failed to walk directories for root %s: %t", root, e);
                }
            }

            logger.debug("Total directories registered: %d", totalDirs);

            // If still hasn't been tested, test it
            if (wsWorking == null) {
                logger.debug("Testing watch service functionality");
                wsWorking = testWatchService(Duration.ofMillis(200));

                // If the watch service is working, log it
                if (wsWorking) {
                    logger.debug("Watch service test passed - using WatchService for file monitoring");
                } else {
                    logger.debug("Watch service test failed - falling back to polling only");
                }
            } else {
                logger.debug("Watch service status: %s", wsWorking ? "working" : "not working");
            }
        } catch (Throwable t) {
            logger.error("Failed to initialize watch service: %t", t);
            t.printStackTrace();
            wsWorking = false;
        }
    }

    /**
     * Test the watch service
     * @param timeout The timeout
     * @return True if the watch service is working, false otherwise, null if still hasn't been tested
     */
    private Boolean testWatchService(Duration timeout) {
        // usa primeira root para o teste
        Path root = roots.get(0);
        
        // Skip test if directory doesn't exist
        if (!Files.exists(root)) {
            logger.debug("Skipping watch service test for root %s: directory does not exist", root);
            return false;
        }
        
        Path tmp = root.resolve(".ws-test-" + UUID.randomUUID());
        logger.debug("Starting watch service test with temporary file: %s", tmp);

        try {
            Files.writeString(tmp, "x");
            logger.debug("Test file created: %s", tmp);
        } catch (Exception e) {
            logger.debug("Failed to create test file for watch service test: %t", e);
            // Well, we can't write to the file, so we can assume the watch service is working
            // and fallback for polling
            return false;
        }

        // Get the start time
        long start = System.currentTimeMillis();

        // Wait for the watch service to detect the file
        while (System.currentTimeMillis() - start < timeout.toMillis()) {
            WatchKey k = ws.poll();

            // If the watch key is not null, the watch service is working
            if (k != null) {
                logger.debug("Watch service detected test file event");
                k.pollEvents();
                k.reset();

                try {
                    Files.deleteIfExists(tmp);
                    logger.debug("Test file deleted: %s", tmp);
                } catch (Exception e) {
                    logger.debug("Failed to delete test file: %t", e);
                }

                // Return true
                return true;
            }

            // Sleep for 10 milliseconds
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                logger.warn("Watch service test interrupted: %t", e);
                break;
            }
        }

        try {
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            logger.debug("Failed to delete test file after timeout: %t", e);
        }

        long elapsed = System.currentTimeMillis() - start;
        logger.debug("Watch service test timed out after %dms", elapsed);
        // Return false
        return false;
    }

    /**
     * Poll the hybrid watcher
     * @return The list of entries
     */
    public List<Entry> poll() {
        List<Entry> out = new ArrayList<>();
        logger.debug("Polling hybrid watcher");

        if (wsWorking) {
            logger.debug("Polling watch service");
            int beforeSize = out.size();
            pollWatchService(out);
            int watchServiceEvents = out.size() - beforeSize;
            if (watchServiceEvents > 0) {
                logger.debug("Watch service detected %d event(s)", watchServiceEvents);
            }
        } else {
            logger.debug("Watch service not available, skipping watch service poll");
        }

        long now = System.currentTimeMillis();
        if (now - lastPoll >= interval.toMillis()) {
            logger.debug("Polling filesystem (interval reached: %dms)", now - lastPoll);
            int beforeSize = out.size();
            pollFilesystem(out);
            int filesystemEvents = out.size() - beforeSize;

            if (filesystemEvents > 0) {
                logger.debug("Filesystem poll detected %d event(s)", filesystemEvents);
            }

            lastPoll = now;
        } else {
            long remaining = interval.toMillis() - (now - lastPoll);
            logger.debug("Filesystem poll skipped, %dms remaining until next poll", remaining);
        }

        if (!out.isEmpty()) {
            logger.debug("Poll completed: %d total event(s) detected", out.size());
            for (Entry entry : out) {
                logger.debug("Event: %s - %s", entry.type(), entry.path());
            }
        }

        return out;
    }

    /**
     * Poll the watch service
     * @param out The list of entries
     */
    private void pollWatchService(List<Entry> out) {
        WatchKey key;
        int keyCount = 0;
        while ((key = ws.poll()) != null) {
            keyCount++;
            // Get the directory
            Path dir = (Path) key.watchable();
            logger.debug("Processing watch key from directory: %s", dir);

            // Iterate over the events
            int eventCount = 0;
            for (WatchEvent<?> ev : key.pollEvents()) {
                eventCount++;
                // Get the kind of the event
                WatchEvent.Kind<?> k = ev.kind();

                // Get the path of the event
                Path p = dir.resolve((Path) ev.context());

                logger.debug("Watch service event: %s - %s", k, p);

                // It's a create event
                if (k == StandardWatchEventKinds.ENTRY_CREATE) {	
                    out.add(new Entry(p, EventType.CREATED));
                    logger.debug("Added CREATED event: %s", p);
                } else
                // It's a modify event
                if (k == StandardWatchEventKinds.ENTRY_MODIFY) {
                    out.add(new Entry(p, EventType.MODIFIED));
                    logger.debug("Added MODIFIED event: %s", p);
                } else
                // It's a delete event
                if (k == StandardWatchEventKinds.ENTRY_DELETE) {
                    out.add(new Entry(p, EventType.DELETED));
                    logger.debug("Added DELETED event: %s", p);
                } else {
                    logger.debug("Unhandled watch event kind: %s for %s", k, p);
                }
            }

            logger.debug("Processed %d event(s) from watch key", eventCount);

            boolean reset = key.reset();
            if (!reset) {
                logger.warn("Watch key is no longer valid for directory: %s", dir);
            }
        }

        if (keyCount > 0) {
            logger.debug("Processed %d watch key(s)", keyCount);
        }
    }

    /**
     * Poll the filesystem
     * @param out The list of entries
     */
    private void pollFilesystem(List<Entry> out) {
        logger.debug("Starting filesystem poll");
        Set<Path> current = new HashSet<>();
        int totalFilesScanned = 0;

        for (Path root : roots) {
            // Skip if directory doesn't exist
            if (!Files.exists(root)) {
                logger.debug("Skipping filesystem poll for root %s: directory does not exist", root);
                continue;
            }
            
            try (var stream = Files.walk(root)) {
                int fileCount = (int) stream.filter(Files::isRegularFile)
                        .peek(current::add)
                        .count();
                totalFilesScanned += fileCount;
                logger.debug("Scanned %d files from root: %s", fileCount, root);
            } catch (IOException e) {
                logger.warn("Failed to walk filesystem for root %s: %s", root, e.getMessage());
            }
        }

        logger.debug("Total files scanned: %d, currently tracking: %d", totalFilesScanned, lastModified.size());

        int deletedCount = 0;
        for (Path old : Set.copyOf(lastModified.keySet())) {
            if (!current.contains(old)) {
                out.add(new Entry(old, EventType.DELETED));
                lastModified.remove(old);
                deletedCount++;
                logger.debug("File deleted: %s", old);
            }
        }

        if (deletedCount > 0) {
            logger.debug("Detected %d deleted file(s)", deletedCount);
        }

        int createdCount = 0;
        int modifiedCount = 0;
        for (Path p : current) {
            long mod = getModTime(p);
            Long prev = lastModified.get(p);

            // If the previous modification time is null, it's a create event
            if (prev == null) {
                out.add(new Entry(p, EventType.CREATED));
                createdCount++;
                logger.debug("File created: %s", p);
            } else
            // If the modification time is greater than the previous modification time, it's a modify event
            if (mod > prev) {
                out.add(new Entry(p, EventType.MODIFIED));
                modifiedCount++;
                logger.debug("File modified: %s (prev: %d, new: %d)", p, prev, mod);
            }

            lastModified.put(p, mod);
        }

        if (createdCount > 0) {
            logger.debug("Detected %d created file(s)", createdCount);
        }
        if (modifiedCount > 0) {
            logger.debug("Detected %d modified file(s)", modifiedCount);
        }

        logger.debug("Filesystem poll completed. Now tracking %d files", lastModified.size());
    }

    /**
     * The type of event
     */
    public enum EventType {
        /**
         * The file has been created
         */
        CREATED,

        /**
         * The file has been modified
         */
        MODIFIED,

        /**
         * The file has been deleted
         */
        DELETED
    }

    /**
     * The entry of the hybrid watcher
     */
    public record Entry(Path path, EventType type) {}
}
