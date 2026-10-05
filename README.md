# OP Trade Cycling

[Русский](README_RU.md)

A client-only Fabric mod for Minecraft **1.20.1** that lets players with permission to use vanilla `/data` commands reroll an **untraded** villager's offers without installing anything on the server.

> This mod is intended for servers/worlds where you are allowed to use operator commands. It does not bypass server permissions.

## Features

- Client-only: nothing needs to be installed on the server.
- Works in singleplayer with cheats and on servers where the player can use `/data` and `/execute` (normally permission level 2+).
- Rerolls the exact villager currently being traded with.
- Keeps the merchant screen visually open while offers are refreshed.
- Refuses to reroll villagers that have already been traded with by default.
- Optional dangerous bypass for intentionally resetting already-traded villagers.
- Uses multiple safety checks, including villager UUID tracking and a server-side `Xp:0` guard in safe mode.
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

## Dangerous bypass

On first launch the mod creates:

`config/optradecycling.json`

Default configuration:

```json
{
  "dangerousBypassUsedTrades": false
}
```

Safe mode is the default and blocks any villager that has already been traded with.

Setting `dangerousBypassUsedTrades` to `true` deliberately disables the used-trade protection. A reroll can then fully reset an already-traded villager:

- existing offers are discarded;
- trade XP is reset to `0`;
- villager trading level is reset to novice (level 1);
- new offers are generated for the same profession;
- gossip/reputation is preserved.

The config is re-read every time the reroll key is pressed, so this option can be toggled without restarting Minecraft.

**Warning:** this mode can permanently erase valuable trades and villager trading progress. Use it only when that is explicitly what you want.

## Safety

With the bypass disabled, OP Trade Cycling is deliberately conservative. A villager that is observed as traded is locked for the rest of the connection, and the destructive server commands additionally target the exact villager UUID with `Xp:0`.

The exact UUID requirement remains active even in bypass mode.

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
