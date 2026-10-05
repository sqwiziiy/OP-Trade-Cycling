# Changelog

## Unreleased

- Add an opt-in `dangerousBypassUsedTrades` config option.
- Allow intentionally rerolling villagers that have already been traded with.
- In bypass mode, fully reset trade XP and villager trade level back to novice before generating new offers.
- Preserve exact UUID targeting even in bypass mode.
- Reload the config on every reroll key press, so bypass can be toggled without restarting the game.

## 0.1.4 — Initial public release

- Client-only villager trade cycling for Fabric 1.20.1 using vanilla operator commands.
- Keeps the merchant GUI visually open while offers are refreshed.
- Requires the exact villager UUID; no nearest-villager fallback.
- Blocks rerolling when offers were used or the villager has trade XP.
- Locks observed traded villager UUIDs for the rest of the connection.
- Adds a final server-side exact-UUID + `Xp:0` guard before destructive commands.
- Places English and Russian keybind translations in the correct Minecraft resource path.

## 0.1.3

- Pre-release build used for final gameplay testing.
