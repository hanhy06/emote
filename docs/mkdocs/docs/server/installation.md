# Installation and Configuration

## File placement

Place the Emote mod file matching the server's Minecraft and Fabric Loader versions in `mods/`. The first server startup creates this structure:

```text
config/emote/
├── config.json
├── emotes.json
└── animations/
```

Place Animation or Sequence JSON exported by the web converter under `animations/`. Subdirectories are scanned as well, so files may be grouped by creator or pack.

```text
animations/
├── default/
│   ├── wave.json
│   └── dance.json
└── idle/
    ├── sit-down.json
    ├── sit-idle.json
    └── sit-sequence.json
```

File names are only for organization. The actual emote ID comes from `id` inside the JSON. If different files declare the same ID, all files with that ID are rejected.

## `config.json`

```json
{
  "schema_version": 1,
  "menu_page_size": 6,
  "mineskin_api_key": "",
  "mineskin_poll_interval_seconds": 3,
  "mineskin_cache_retention_days": 30,
  "mineskin_cache_max_mib": 256,
  "max_active_display_entities": 512
}
```

| Field | Behavior |
|---|---|
| `menu_page_size` | Number of emotes shown on each `/emote` menu page. Minimum: `1`. |
| `mineskin_api_key` | Used to convert player skins into display textures. Player-skin application is disabled when empty. |
| `mineskin_poll_interval_seconds` | Interval for checking MineSkin job status. Range: `1`–`60` seconds. |
| `mineskin_cache_retention_days` | Skin-cache retention period. Range: `1`–`3650` days. |
| `mineskin_cache_max_mib` | Maximum skin-cache size in MiB. |
| `max_active_display_entities` | Server-wide limit on display entities activated by Emote. A value of `0` prevents creation. |

## Reloading

Run this after modifying or adding files:

```text
/emote reload
```

Reloading performs these operations:

1. Reads `config.json` and `emotes.json` again.
2. Stops every emote currently playing.
3. Rebuilds Animations and Sequences from `animations/`.
4. Synchronizes the wheel list of connected clients.

Invalid Animation files are skipped individually. If `config.json` or `emotes.json` is invalid, the last valid in-memory configuration remains active and the cause is written to the server log.

The command result shows the number of disabled IDs and permission groups from `emotes.json`, followed by the detected and loaded emote file counts. The loaded count is green when it matches the detected count and red when it differs. Check the server log for details when the counts differ.

## Verifying the installation

```text
/emote list
```

This command shows each loaded ID, name, node count, duration, source file, playback mode, and player visibility. If an entry is listed but players cannot see it, check [Access Control](access-control.md) before investigating file loading.
