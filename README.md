<div align="center">

# MDevTools

> Development toolkit for Hytale mod authors

**Log cleanup, global log levels, and hot-reload of mods without full server restarts.**

[![Java](https://img.shields.io/badge/Language-Java-orange.svg)](#)
[![Hytale](https://img.shields.io/badge/Platform-Hytale-purple.svg)](#)

[Overview](#overview) • [Features](#features) • [Configuration](#configuration) • [Installation](#installation)

</div>

---

## Overview

MDevTools streamlines Hytale mod development by watching `mods`, `builtin`, and `earlyplugins` (plus optional extra paths) and reloading JAR/ZIP files when they change. It also cleans old logs on startup and can set a global log level for all server loggers.

Hybrid file monitoring combines Java `WatchService` with polling fallback for Docker, VMs, and network filesystems.

---

## Features

- Log cleanup on startup.
- Global log level configuration with per-logger skip list.
- Automatic hot-reload of modified mods.
- Automatic loading of new mods dropped into watched directories.
- Exclusion patterns for mods that must not reload.
- File stability detection before reload (avoids half-written uploads).
- Polling fallback for container and NFS/CIFS mounts.

### Hot-reload behavior

When a file changes, MDevTools waits until size is stable, then reloads or loads the mod depending on whether the plugin ID is already active.

Ideal for rapid iteration, CI artifact drops, and remote Docker development.

---

## Configuration

Paths:

- `config/com.machina/mdevtools` (preferred)
- fallback: `mods/com.machina/mdevtools`

JSON5 format (comments and trailing commas allowed).

### Log settings

| Key | Purpose |
| --- | ------- |
| `logs.cleanupOnStartup.enabled` | Remove old log/lock files on startup |
| `logs.global.level` | Global level (`INFO`, `DEBUG`, `WARNING`, ...) |
| `logs.global.skip` | Logger names to skip when applying global level |

### Mod reload settings

| Key | Purpose |
| --- | ------- |
| `mods.reload.enabled` | Master hot-reload switch |
| `mods.reload.delayMs` | Delay before stability check |
| `mods.reload.fileStabilityCheckMs` | Time file size must stay stable |
| `mods.reload.additionalDirectories` | Extra directories to watch |
| `mods.reload.exclude` | Wildcard patterns (mod IDs and filenames) |
| `mods.reload.unloadWhenDeleted` | Unload mod when file is deleted |

Example:

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

---

## Installation

1. Place the MDevTools JAR in the server's `mods` directory.
2. Configure `config.json5` as needed.
3. Restart the server.

---

## Community

- Support: [machinastudios.net/support-us](https://machinastudios.net/support-us)
- Discord: [discord.gg/QAFrzj48EN](https://discord.gg/QAFrzj48EN)

---

## License

Part of the Machina plugin ecosystem for Hytale.
