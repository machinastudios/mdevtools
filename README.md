# MDevTools - Development tools for Hytale servers and modders

Development tools plugin for Hytale servers and modders that provides automated log cleanup, configurable global log level, and mod hot-reload capabilities.

## Features

- **Log Cleanup**: Automatically removes old log files on startup, keeping only the most recent one
- **Log Level**: Sets the global log level for all server loggers on startup (e.g. `INFO`, `DEBUG`, `WARNING`)
- **Mod Hot-Reload**: Automatically reloads mods when files (`.jar` or `.zip`) are updated in the `mods`, `builtin`, or `earlyplugins` directories, without requiring a full server restart
- **Auto-Load New Mods**: Automatically loads new mods when they are added to these directories, without requiring a full server restart
- **Mod Exclusion**: Exclude specific mods from hot-reload using wildcard patterns (supports both mod IDs and file names)

### How Mod Hot-Reload Works

The mod hot-reload feature monitors the `mods`, `builtin`, and `earlyplugins` directories (plus any `mods.reload.additionalDirectories`) for changes to `.jar` or `.zip` files. It can both reload existing mods when they are updated and automatically load new mods when they are added to these directories. When a change is detected:

1. **File Stability Check**: Before attempting to load or reload, the system waits for the file to be completely written. This prevents attempting to load incomplete files during slow uploads or file copies:
   - Waits a configurable delay (`mods.reload.delayMs`) after file detection
   - Verifies the file size is stable for a configurable duration (`mods.reload.fileStabilityCheckMs`)
   - Resets the timer if the file is still being written (detected through new change events)
2. **Manifest Reading**: The plugin reads the `manifest.json` file from the modified or new mod to extract its identifier and dependencies
3. **Dependency Handling**: It intelligently handles dependencies (`Dependencies` and `OptionalDependencies`) by temporarily adjusting plugin states to work around internal bugs in the plugin system
4. **Plugin Load/Reload**: 
   - For new mods: The plugin is loaded using the Hytale server's `PluginManager.load()` method
   - For existing mods: The modified plugin is reloaded using the Hytale server's `PluginManager.reload()` method
5. **State Restoration**: Any temporary changes made to dependency plugins are reverted, ensuring the plugin system remains consistent

This allows developers to quickly test changes to their mods and add new mods without restarting the entire server, significantly speeding up the development workflow. The file stability check ensures reliable operation even with slow network transfers or file copies in progress.

#### Mod Exclusion

You can exclude specific mods from hot-reload by configuring exclusion patterns. This is useful for:
- **Core/System Mods**: Exclude critical mods that should only be reloaded manually
- **Large Mods**: Exclude mods that take too long to reload during development
- **Problematic Mods**: Exclude mods that cause issues when hot-reloaded

Exclusion patterns support wildcards (`*` for any characters, `?` for single character) and will match against both:
- **Mod IDs**: The full mod identifier (e.g., `com.example:mymod`)
- **File Names**: The JAR/ZIP file name (e.g., `mymod.jar`)

**Example patterns:**
- `com.example:*` - Excludes all mods from the `com.example` group
- `*:core` - Excludes all mods with ID ending in `:core`
- `mymod.jar` - Excludes the specific file
- `*test*` - Excludes any mod ID or filename containing "test"

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

- **Configurable Delay**: After a file change is detected, the system waits `mods.reload.delayMs` before attempting to reload
- **Size Stability Check**: The system verifies the file size remains unchanged for `mods.reload.fileStabilityCheckMs` to ensure the file is completely written
- **Dynamic Reset**: If new change events are detected while waiting, the timer is reset, ensuring the system waits for the file transfer to complete
- **Works with Slow Transfers**: This system handles slow network uploads, large file copies, and any scenario where files are written incrementally

## Installation

1. Place the MDevTools JAR file in your server's `builtin` directory (not in the `mods` directory—this plugin must be explicitly placed in `builtin`). **If you place it in the mods folder, things can go wrong.**
2. Configure using `config.json5` as needed.
3. Restart the server.

The plugin will automatically start monitoring for mod changes, clean up logs, and apply the configured log level on startup.

## Configuration

```json5
{
  "logs": {
    "cleanupOnStartup": {
      // Whether to cleanup logs and lock files on startup
      // Default: true
      "enabled": true
    },
    "global": {
      // Global log level for all server loggers (e.g. INFO, DEBUG, WARNING, SEVERE). Set null to disable
      // Default: "INFO"
      "level": "INFO",
      // Logger names to skip when applying global level (e.g. verbose loggers)
      // Default: ["PacketLogging", "WorldChunk"]
      "skip": ["PacketLogging", "WorldChunk"]
    }
  },
  "mods": {
    "reload": {
      // Whether to enable mod hot-reload
      // Default: true
      "enabled": true,
      // Delay in milliseconds before reloading a mod after it's detected (to ensure file is fully written)
      // Default: 1000
      "delayMs": 1000,
      // Time in milliseconds to wait checking if file size is stable before reloading
      // Default: 500
      "fileStabilityCheckMs": 500,
      // Additional directories to watch for mod updates (beyond mods/, builtin/, earlyplugins/)
      // Default: []
      "additionalDirectories": [],
      // Mods to exclude from hot-reload (supports wildcards: * and ?)
      // Matches against both mod IDs (group:id) and file names
      // Default: []
      "exclude": [
        "com.example:core",
        "*:system",
        "test*.jar"
      ],
      // Whether to unload a mod when it's deleted from the filesystem
      // Default: false
      "unloadWhenDeleted": false
    }
  }
}
```

**Configuration Notes:**

- **`logs.cleanupOnStartup.enabled`**: When `true`, old log and lock files are removed on startup, keeping only the most recent one.
- **`logs.global.level`**: Java `Level` name (e.g. `INFO`, `DEBUG`, `WARNING`, `SEVERE`) applied to all server loggers on startup. Set `null` to disable the log-level task.
- **`logs.global.skip`**: Logger names to skip when applying the global level (e.g. `PacketLogging`, `WorldChunk`). Default: `["PacketLogging", "WorldChunk"]`.
- **`mods.reload.enabled`**: When `false`, disables mod hot-reload entirely.
- **`mods.reload.delayMs`**: Initial delay after a file change is detected before starting the stability check. Increase if files are detected too early (e.g. during slow uploads).
- **`mods.reload.fileStabilityCheckMs`**: Duration to verify file size stability. The file must not change size during this period. Increase if mods are loaded while still being written.
- **`mods.reload.additionalDirectories`**: Extra directories to watch for mod changes. Default watched paths are `mods/`, `builtin/`, and `earlyplugins/`.
- **`mods.reload.exclude`**: Wildcard patterns (`*`, `?`) to exclude mods from hot-reload. Matches mod IDs (`group:id`) and file names. Patterns are cached.
- **`mods.reload.unloadWhenDeleted`**: When `true`, mods are unloaded when their JAR/ZIP is deleted. Use with caution (e.g. avoid moving/renaming mods temporarily).

## Community

💬 **Join our Discord community!**

Get help, share your ideas, and connect with other developers:
- 🆘 **Support**: Get help with setup and troubleshooting
- 💡 **Suggestions**: Share your ideas and feedback
- 🤝 **Community**: Connect with other Hytale developers

**👉 [Join Discord Server](https://discord.gg/QAFrzj48EN)**

---

**Developed by Machina Studios**
