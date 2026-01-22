package com.machina.mdevtools.events;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.interface_.ChatMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.machina.mdevtools.tasks.ModReloadTask;
import com.machina.shared.mechanics.PacketInterceptor.InterceptedPacketEvent;
import com.machina.shared.util.PlayerUtil;

public class CommandInterceptorEvent {
    public static void onCommand(InterceptedPacketEvent<ChatMessage> event) {
        String message = event.getPacket().message;

        // Ignore if the message is null
        if (message == null) {
            return;
        }

        // Split the message into parts
        String parts[] = message.split(" ");

        // Ignore if the event doesn't start with "/plugin"
        if (!parts[0].equals("/plugin")) {
            return;
        }

        // Get the player reference
        PlayerRef playerRef = event.getPlayerRef();

        // If the player is null, ignore
        if (playerRef == null) {
            return;
        }

        var player = PlayerUtil.getPlayer(playerRef);

        // Ignore if player has no permission (OP)
        if (!PlayerUtil.isOp(player)) {
            event.setCancelled(true);
            playerRef.sendMessage(Message.raw("You do not have permission to use this command"));
            return;
        }

        // Get the sub command
        String subCommand = parts[1];

        // Switch on the sub command
        switch (subCommand) {
            // Reload or load a plugin
            case "load":
            case "reload": {
                    // Requires a third argument
                    if (parts.length < 3) {
                        event.setCancelled(true);
                        playerRef.sendMessage(Message.raw("Usage: /plugin reload <plugin>"));
                        return;
                    }

                    // Try retrieving the plugin from the plugin manager
                    PluginBase plugin = PluginManager.get().getPlugin(PluginIdentifier.fromString(parts[2]));

                    // If the plugin is not found, send a message to the player
                    if (plugin == null) {
                        event.setCancelled(true);
                        playerRef.sendMessage(Message.raw("Plugin not found: " + parts[2]));
                        return;
                    }

                    // Add the plugin to the pending list
                    ModReloadTask.addPendingPlugin(plugin.getIdentifier());

                    // Cancel the event
                    event.setCancelled(true);
                break;
            }

            default:
                break;
        }
    }
}
