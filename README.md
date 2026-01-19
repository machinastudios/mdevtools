# MDevTools - Development tools for Hytale servers and modders

Development tools plugin for Hytale servers and modders that provides automated log cleanup and mod hot-reload capabilities.

## Features

- **Log Cleanup**: Automatically removes old log files on startup, keeping only the most recent one
- **Mod Hot-Reload**: Automatically reloads mods when files (`.jar` or `.zip`) are updated in the `mods` or `builtin` directories, without requiring a full server restart
- **Auto-Load New Mods**: Automatically loads new mods when they are added to the `mods` or `builtin` directories, without requiring a full server restart

### How Mod Hot-Reload Works

The mod hot-reload feature monitors both the `mods` and `builtin` directories for changes to `.jar` or `.zip` files. It can both reload existing mods when they are updated and automatically load new mods when they are added to these directories. When a change is detected:

1. **File Stability Check**: Before attempting to load or reload, the system waits for the file to be completely written. This prevents attempting to load incomplete files during slow uploads or file copies:
   - Waits a configurable delay (`mods.reloadDelayMs`) after file detection
   - Verifies the file size is stable for a configurable duration (`mods.fileStabilityCheckMs`)
   - Resets the timer if the file is still being written (detected through new change events)
2. **Manifest Reading**: The plugin reads the `manifest.json` file from the modified or new mod to extract its identifier and dependencies
3. **Dependency Handling**: It intelligently handles dependencies (`Dependencies` and `OptionalDependencies`) by temporarily adjusting plugin states to work around internal bugs in the plugin system
4. **Plugin Load/Reload**: 
   - For new mods: The plugin is loaded using the Hytale server's `PluginManager.load()` method
   - For existing mods: The modified plugin is reloaded using the Hytale server's `PluginManager.reload()` method
5. **State Restoration**: Any temporary changes made to dependency plugins are reverted, ensuring the plugin system remains consistent

This allows developers to quickly test changes to their mods and add new mods without restarting the entire server, significantly speeding up the development workflow. The file stability check ensures reliable operation even with slow network transfers or file copies in progress.

#### File Monitoring: Hybrid Approach with Polling Fallback

MDevTools uses a **hybrid file monitoring approach** that combines Java's `WatchService` (for fast, event-driven detection) with periodic polling as a fallback mechanism. The system automatically detects if `WatchService` is working correctly and falls back to polling when necessary.

**Why polling fallback is necessary:**

- **Docker/Container Environments**: When running in Docker containers with mounted volumes, the `WatchService` may not receive filesystem events correctly. This happens because:
  - File system events need to propagate through multiple layers (host filesystem → Docker volume → container filesystem)
  - Container filesystems often use bind mounts or volumes that don't properly forward inotify events
  - Network filesystems (NFS, CIFS) commonly used in container environments don't reliably support file watching events

- **Virtual Machines**: Similar issues occur in VMs where file system events may not be properly forwarded from the host to the guest system

- **Remote/Network File Systems**: When mod directories are on network-mounted drives, `WatchService` often fails silently

The polling fallback ensures that mod hot-reload works reliably in **all environments**, including Docker, development containers, and remote development setups. While polling may have slightly higher latency than event-driven monitoring, it guarantees that file changes are always detected regardless of the underlying filesystem or containerization layer.

#### Smart File Stability Detection

To handle slow uploads and file transfers gracefully, MDevTools implements a smart file stability detection system:

- **Configurable Delay**: After a file change is detected, the system waits `mods.reloadDelayMs` before attempting to reload
- **Size Stability Check**: The system verifies the file size remains unchanged for `mods.fileStabilityCheckMs` to ensure the file is completely written
- **Dynamic Reset**: If new change events are detected while waiting, the timer is reset, ensuring the system waits for the file transfer to complete
- **Works with Slow Transfers**: This system handles slow network uploads, large file copies, and any scenario where files are written incrementally

## Installation

1. Place the MDevTools JAR file in your server's `builtin` directory (not in the `mods` directory - this plugin must be explicitly placed in `builtin`)
If you place it in the mods folder, things can get really wrong.
2. Configure using `config.json5` as needed
3. Restart the server

The plugin will automatically start monitoring for mod changes and clean up logs on startup.

## Configuration

```json5
{
  "logs": {
    // Whether to cleanup logs and lock files on startup
    "cleanupOnStartup": true
  },
  "mods": {
    // Whether to hot reload mods when they are updated
    "restartServerWhenUpdated": true,
    // Delay in milliseconds before reloading a mod after it's detected (to ensure file is fully written)
    // Default: 1000ms (1 second)
    "reloadDelayMs": 1000,
    // Time in milliseconds to wait checking if file size is stable before reloading
    // Default: 500ms (0.5 seconds)
    "fileStabilityCheckMs": 500
  }
}
```

**Configuration Notes:**
- `reloadDelayMs`: The initial delay after a file change is detected before starting the stability check. Increase this if you experience issues with files being detected too early (e.g., during slow uploads).
- `fileStabilityCheckMs`: The duration to monitor file size stability. The file must not change size during this period to be considered stable. Increase this if files are being loaded while still being written.

## Community

💬 **Join our Discord community!**

Get help, share your ideas, and connect with other developers:
- 🆘 **Support**: Get help with setup and troubleshooting
- 💡 **Suggestions**: Share your ideas and feedback
- 🤝 **Community**: Connect with other Hytale developers

**👉 [Join Discord Server](https://discord.gg/QAFrzj48EN)**

---

**Developed by Machina Studios**
