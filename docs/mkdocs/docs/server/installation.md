# Installation

## File placement

Place the Emote mod file matching the server's Minecraft and Fabric Loader versions in `mods/`.<br>
The first server startup creates this structure:

```text
config/emote/
├── config.json
├── emotes.json
└── emote/
```

Place Emote JSON exported by the web converter under `emote/`.

```text
emote/
├── default/
│   ├── wave.json
│   └── dance.json
└── idle/
    ├── sit-down.json
    ├── sit-idle.json
    └── sit-sequence.json
```

The directory is scanned recursively, so file location within it does not matter.<br>
File names are only for organization. The mod identifies emotes by `id` and rejects duplicate IDs.

---

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

| Field                            | Behavior                                                                                            |
|----------------------------------|-----------------------------------------------------------------------------------------------------|
| `menu_page_size`                 | Number of emotes shown on each `/emote` menu page. Minimum: `1`.                                    |
| `mineskin_api_key`               | Used to convert player skins into display textures. Player-skin application is disabled when empty. |
| `mineskin_poll_interval_seconds` | Interval for checking MineSkin job status. Range: `1`–`60` seconds.                                 |
| `mineskin_cache_retention_days`  | Skin-cache retention period. Range: `1`–`3650` days.                                                |
| `mineskin_cache_max_mib`         | Maximum skin-cache size in MiB.                                                                     |
| `max_active_display_entities`    | A value of 0 allows an unlimited number of active display entities.                                 |
