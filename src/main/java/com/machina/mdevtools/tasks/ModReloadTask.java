package com.machina.mdevtools.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ShutdownReason;

public class ModReloadTask extends Thread {
    private boolean running = true;

    public ModReloadTask() {
        super("Mod Reload Task");
    }

    @Override
    public void run() {
        // Watch for .zip and .jar files in the mods directory
        Path modsPath = new File("mods").toPath();

        try {
            // Create a watch service
            WatchService watchService = FileSystems.getDefault().newWatchService();

            // Register the mods path for watching
            modsPath.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            );

            // Start the watch loop
            while (running) {
                // Take a key from the watch service
                WatchKey key = watchService.take();

                // Process events for this key
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (
                        kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_DELETE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY
                    ) {
                        // Check if the event is a file
                        if (!(event.context() instanceof Path)) {
                            continue;
                        }

                        // Get the file path
                        Path filePath = (Path) event.context();

                        boolean isZip = filePath.getFileName().toString().endsWith(".zip");
                        boolean isJar = filePath.getFileName().toString().endsWith(".jar");
                        boolean isMod = isZip || isJar;

                        // Check if the file is a .zip or .jar file
                        if (!isMod) {
                            continue;
                        }

                        // Restart the server
                        HytaleServer.get().shutdownServer(ShutdownReason.SHUTDOWN.withMessage("Mods updated"));

                        // Stop the task
                        running = false;

                        break;
                    }
                }

                // Reset the key to receive further events
                boolean valid = key.reset();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
