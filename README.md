# OP Trade Cycling

[Русский](README_RU.md)

A client-only Fabric mod for Minecraft **1.20.1** that lets players with permission to use vanilla `/data` commands reroll an **untraded** villager's offers without installing anything on the server.

> This mod is intended for servers/worlds where you are allowed to use operator commands. It does not bypass server permissions.

## Features

- Client-only: nothing needs to be installed on the server.
- Works in singleplayer with cheats and on servers where the player can use `/data` and `/execute` (normally permission level 2+).
- Rerolls the exact villager currently being traded with.
- Keeps the merchant screen visually open while offers are refreshed.
- Refuses to reroll villagers that have already been traded with.
- Uses multiple safety checks, including villager UUID tracking and a server-side `Xp:0` guard.
- No nearest-villager fallback: if the exact target cannot be identified, the reroll is refused.

## Requirements

- Minecraft **1.20.1**
- Fabric Loader
- Fabric API
- Java 17+

## Usage

1. Install the mod and Fabric API on the **client**.
2. Open the trading screen of an untraded villager with a profession.
3. Press the configured **Cycle villager trades (OP)** key. The default key is **C** and can be changed in Minecraft Controls.
4. The offers are rerolled and the trading screen is reopened in place.

The mod refuses to run when the player lacks the required commands, offers are not fully synced, the villager has already been traded with, or the exact villager cannot be identified.

## Safety

OP Trade Cycling is deliberately conservative. A villager that is observed as traded is locked for the rest of the connection, and the destructive server commands additionally target the exact villager UUID with `Xp:0`.

Even with these checks, use backups when testing command-driven gameplay changes on important worlds.

## Releases

Releases are built automatically by GitHub Actions and published to both GitHub Releases and Modrinth.

Modrinth: https://modrinth.com/mod/op-trade-cycling

## Building

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew build
```

The release JAR is written to `build/libs/`.

## License

All rights reserved. See [LICENSE](LICENSE).
