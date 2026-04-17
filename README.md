# Simple AFK Bot Mod for Minecraft 26.1

A simple, standalone server-side Fabric mod that spawns fake player bots to keep chunks loaded and farms running. No Carpet mod or client-side install required — just drop it in and go.

## Installation

1. Make sure your server is running **Fabric Loader** and **Fabric API** for Minecraft 26.1
2. Drop the `afk-bot-mod-1.0.0.jar` file into your server's `mods/` folder
3. Restart the server
4. No client-side installation needed — this is server-only

## Commands

| Command | Who can use | Description |
|---|---|---|
| `/bot spawn <name>` | All players | Spawns a bot at your current location |
| `/bot remove <name>` | All players | Removes your own bot (OPs can remove anyone's) |
| `/bot list` | All players | Shows all active bots and the server limit |
| `/bot removeall` | OPs only | Removes every bot on the server |
| `/bot config` | OPs only | Shows current bot limit settings |
| `/bot config maxPerPlayer <number>` | OPs only | Set max bots per player (default: 2) |
| `/bot config maxTotal <number>` | OPs only | Set max bots server-wide (default: 10) |

## Limits

- Each player can have up to **2 bots** at a time (configurable)
- The server can have up to **10 bots** total (configurable)
- OPs/admins bypass both limits
- Use `/bot config` to change limits in-game (resets on server restart)

## Features

- **Chunk loading**: Bots act as real players, so the full entity-processing radius around them stays active (mobs spawn, redstone runs, crops grow, hoppers work)
- **Death handling**: If a bot dies (falls in lava, killed by mobs, etc.), it is automatically removed from the server
- **Auto-cleanup**: All bots are removed if the server has no real players for 5 minutes
- **Ownership**: Players can only remove their own bots. OPs can manage all bots

## Building from Source

Requires Java 25+ and the Fabric toolchain.

1. Download the Fabric mod template from [fabricmc.net/develop/template](https://fabricmc.net/develop/template/) for Minecraft 26.1
2. Replace the `src/` folder with the mod source files
3. Run `./gradlew build`
4. Find the built jar in `build/libs/`

## Notes

- The bot shows up in the tab list like a regular player
- The bot takes up a player slot on the server
- Bot names must be a single word (no spaces)
- Bots are server-side only — no client mod needed
- Config changes reset on server restart
