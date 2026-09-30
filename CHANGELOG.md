# Changelog

## 0.1.3 — Initial public release

- Client-only villager trade cycling for Fabric 1.20.1 using vanilla operator commands.
- Keeps the merchant GUI visually open while offers are refreshed.
- Requires the exact villager UUID; no nearest-villager fallback.
- Blocks rerolling when offers were used or the villager has trade XP.
- Locks observed traded villager UUIDs for the rest of the connection.
- Adds a final server-side exact-UUID + `Xp:0` guard before destructive commands.
- Includes English and Russian keybind translations.
