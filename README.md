# MDevTools - Development tools for Hytale servers and modders

MDevTools is a development toolkit for Hytale servers and modders that streamlines iteration by automatically cleaning up logs, adjusting global log levels and enabling hot-reload of mods during development without requiring a full server restart.

## Overview

MDevTools improves the development workflow for Hytale mod authors by watching key plugin directories and reloading or loading mods when changes are detected. This removes the need to restart the server each time a JAR or ZIP is updated, significantly improving iteration speed.

It also manages log cleanup to prevent workspace clutter and can set a global log level so that debugging or verbose output can be tuned per session.

## Key Features

* Log cleanup on startup to prevent clutter 🧹
* Global log level configuration for all server loggers 🎛️
* Automatic hot-reload of modified mods during development ♻️
* Automatic loading of new mods dropped into watched directories 📦
* Exclusion patterns to avoid reloading critical or slow mods 🚫
* Hybrid file watching with polling fallback for container environments 🐳
* Smart file stability detection for slow uploads or transfers ⏱️

## Hot-Reload Behavior

When a JAR or ZIP changes in the `mods`, `builtin` or `earlyplugins` directories (or any additional watched paths), MDevTools will schedule a reload and first ensure the file is fully written. This prevents corruption or half-written file loads during SCP uploads, Docker binds or IDE deployments.

Hot-reload supports both loading new mods and reloading existing ones depending on whether a plugin with the same ID is already active.

## Supported Use Cases

Hot-reload is ideal for:

* Rapid iteration during development 🚀
* Testing mod interactions without server restarts 🧩
* CI pipelines that deploy updated plugin artifacts automatically 🏗️
* Remote development environments running in Docker or VM containers 🌐

## Hybrid File Monitoring

MDevTools combines Java's WatchService for event-driven notifications with a polling fallback for container and network environments where inotify or filesystem events do not propagate correctly.

This ensures file change detection remains reliable in:

* Docker and container setups 🐳
* Virtual machines 🖥️
* Network file systems (NFS / CIFS) 🌐
* Development volume mounts 🔁

## File Stability Detection

To ensure files are completely written before reloading mods, MDevTools applies:

* A configurable delay after first detection
* A file size stability window
* Automatic reset of timers when new write events occur

This prevents reloading during partial uploads, IDE builds or slow network transfers.

## Installation

1. Place the MDevTools JAR in the server's `builtin` directory
2. Configure `config.json5` as needed
3. Restart the server

MDevTools will begin monitoring and apply log cleanup and log level adjustments at startup.

## Configuration

Configuration for MDevTools lives in:

* `config/com.machina/mdevtools` (preferred and used when writable)
* fallback: `mods/com.machina/mdevtools` (when the primary path cannot be written)

All configuration is in **JSON5** format for easier editing (comments allowed, trailing commas allowed).

### Log Settings

Log settings control both cleanup and global verbosity:

* `logs.cleanupOnStartup.enabled`: removes old log and lock files on startup (keeps workspace clean)
* `logs.global.level`: sets the global log level for all server loggers (e.g. `INFO`, `DEBUG`, `WARNING`)
* `logs.global.skip`: skip specific noisy loggers when applying the global level (e.g. packet/world spam)

### Mod Reload Settings

Reload settings control hot-reload behavior for mod files:

* `mods.reload.enabled`: master switch for hot-reload
* `mods.reload.delayMs`: delay before checking stability (helps with slow writes)
* `mods.reload.fileStabilityCheckMs`: duration the file must remain size-stable before reload
* `mods.reload.additionalDirectories`: optional extra directories to watch
* `mods.reload.exclude`: wildcard patterns to exclude mods (matches mod IDs and filenames)
* `mods.reload.unloadWhenDeleted`: unload a mod if its file is deleted

Example configuration:

```json5
{
  "logs": {
    "cleanupOnStartup": { "enabled": true },
    "global": {
      "level": "INFO",
      "skip": ["PacketLogging", "WorldChunk"]
    }
  },
  "mods": {
    "reload": {
      "enabled": true,
      "delayMs": 1000,
      "fileStabilityCheckMs": 500,
      "additionalDirectories": [],
      "exclude": ["com.example:core", "*:system", "test*.jar"],
      "unloadWhenDeleted": false
    }
  }
}
```

## Support Development

You can support development here 💖 to help fuel future updates and features:

[https://machinastudios.net/support-us](https://machinastudios.net/support-us)

## Community

💬 **Join our Discord community!**

Get help, share ideas, and connect with other developers 🧑‍💻:

* 🆘 Support and troubleshooting
* 💡 Suggestions and feedback
* 🤝 Community and collaboration

👉 **Join our Discord Server**: [https://discord.gg/QAFrzj48EN](https://discord.gg/QAFrzj48EN)
