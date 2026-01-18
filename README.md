# MDevTools - Development tools for Hytale servers and modders

Development tools plugin for Hytale servers and modders that provides automated log cleanup and mod hot-reload capabilities.

## Features

- **Log Cleanup**: Automatically removes old log files on startup, keeping only the two most recent
- **Mod Hot-Reload**: Automatically restarts the server when mod files (`.jar` or `.zip`) are updated in the `mods` directory

## Configuration

```json5
{
  "logs": {
    // Whether to cleanup logs and lock files on startup
    "cleanupOnStartup": true
  },
  "mods": {
    // Whether to restart the server when mods are updated
    "restartServerWhenUpdated": true
  }
}
```

## Installation

1. Place the MDevTools JAR file in your server's `mods` directory
2. Configure using `config.json5` as needed
3. Restart the server

The plugin will automatically start monitoring for mod changes and clean up logs on startup.

## Community

💬 **Join our Discord community!**

Get help, share your ideas, and connect with other developers:
- 🆘 **Support**: Get help with setup and troubleshooting
- 💡 **Suggestions**: Share your ideas and feedback
- 🤝 **Community**: Connect with other Hytale developers

**👉 [Join Discord Server](https://discord.gg/QAFrzj48EN)**

---

**Developed by Machina Studios**
