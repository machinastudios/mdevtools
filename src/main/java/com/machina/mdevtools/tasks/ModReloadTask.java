package com.machina.mdevtools.tasks;

import com.machina.mdevtools.Task;
import com.machina.mdevtools.tasks.modreload.ModReloadThread;

public class ModReloadTask extends Task {
    /**
     * The mod reload thread
     */
    private ModReloadThread modReloadThread;

    /**
     * The mod reload task constructor
     */
    public ModReloadTask() {
        super("ModReloadTask", "Automatically watches for mod updates and reloads them when they are updated", "mods.reloadOnUpdate");
    }

    @Override
    public void start() {
        // Start the mod reload task
        modReloadThread = new ModReloadThread();
        modReloadThread.start();
    }

    @Override
    public void stop() {
        // Stop the mod reload thread
        modReloadThread.interrupt();
        modReloadThread = null;
    }
}
