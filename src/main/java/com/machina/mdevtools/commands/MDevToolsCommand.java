package com.machina.mdevtools.commands;

import com.machina.shared.model.SuperPluginCommandHandler;

public class MDevToolsCommand extends SuperPluginCommandHandler {
    public MDevToolsCommand() {
        super("mdevtools", "Main command for the MDevTools plugin");

        // Register the sub commands
        addSubCommand(new FileBrowserCommand());
    }
}
