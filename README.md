# MDevTools - Development tools for Hytale servers and modders

Development tools plugin for Hytale servers and modders that provides automated log cleanup and mod hot-reload capabilities.

## Features

- **Log Cleanup**: Automatically removes old log files on startup, keeping only the two most recent
- **Mod Hot-Reload**: Automatically reloads mods when files (`.jar` or `.zip`) are updated in the `mods` or `builtin` directories, without requiring a full server restart

### How Mod Hot-Reload Works

The mod hot-reload feature monitors both the `mods` and `builtin` directories for changes to `.jar` or `.zip` files. When a change is detected:

1. **Manifest Reading**: The plugin reads the `manifest.json` file from the modified mod to extract its identifier and dependencies
2. **Dependency Handling**: It intelligently handles dependencies (`Dependencies` and `OptionalDependencies`) by temporarily adjusting plugin states to work around internal bugs in the plugin system
3. **Plugin Reload**: The modified plugin is reloaded using the Hytale server's `PluginManager.reload()` method
4. **State Restoration**: Any temporary changes made to dependency plugins are reverted, ensuring the plugin system remains consistent

This allows developers to quickly test changes to their mods without restarting the entire server, significantly speeding up the development workflow.

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
    // Whether to restart the server when mods are updated
    "restartServerWhenUpdated": true
  }
}
```

## Community

💬 **Join our Discord community!**

Get help, share your ideas, and connect with other developers:
- 🆘 **Support**: Get help with setup and troubleshooting
- 💡 **Suggestions**: Share your ideas and feedback
- 🤝 **Community**: Connect with other Hytale developers

**👉 [Join Discord Server](https://discord.gg/QAFrzj48EN)**

---

**Developed by Machina Studios**
