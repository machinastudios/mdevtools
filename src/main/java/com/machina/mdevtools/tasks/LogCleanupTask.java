package com.machina.mdevtools.tasks;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

import com.machina.mdevtools.Task;

public class LogCleanupTask extends Task {
    /**
     * The log cleanup task constructor
     */
    public LogCleanupTask() {
        super("LogCleanupTask", "Cleans up logs and lock files on startup", "logs.cleanupOnStartup.enabled");
    }

    @Override
    public void start() {
        // Cleanup all logs and lock files but the last ones
        // Log files have .log and .log.lck extensions
        File logDir = new File("logs");
        
        if (logDir.exists()) {
            File[] files = logDir.listFiles();

            // Check if files is null
            if (files == null) {
                return;
            }

            // Get the two last file names ordering by name
            String[] lastFileNames = Arrays.stream(files)
                .filter(file -> file.isFile() && (file.getName().endsWith(".log") || file.getName().endsWith(".log.lck")))
                .sorted(Comparator.comparing(File::getName, Comparator.reverseOrder()))
                .limit(2)
                .map(File::getName)
                .toArray(String[]::new);

            // Check if lastFileNames is null
            if (lastFileNames == null) {
                return;
            }

            // Check if lastFileNames has two elements
            if (lastFileNames.length != 2) {
                return;
            }

            // Iterate over all files
            for (File file : files) {
                // Check if file is not the last two files
                if (Arrays.asList(lastFileNames).contains(file.getName())) {
                    continue;
                }

                // Check if file is a file and has a .log or .log.lck extension
                if (file.isFile() && (file.getName().endsWith(".log") || file.getName().endsWith(".log.lck"))) {
                    file.delete();
                }
            }
        }
    }
}
