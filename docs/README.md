# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/15c895aea280b546764a0b7f2db2a4cb1f9628c8.gif)

> Special thanks to [Popular Vibe](https://block-display.com/bd/77774) for allowing me to use their animation!

Emote is a server-side emote player that uses Minecraft display entities to play animations created with BD Engine, GeckoLib, and Animated Java.

The mod can be installed on the server only. Installing it on the client also adds an emote wheel and automatic third-person view while an emote is playing.

Compatible emotes can use the playing player’s skin. The web converter converts BD Engine projects and datapacks, GeckoLib Blockbench models, and Animated Java blueprints into Emote animation JSON files.

[![Web converter](https://img.shields.io/badge/Web_converter-0067C0?style=flat-square&logo=githubpages&logoColor=white)](https://hanhy06.github.io/emote/)
[![Modrinth](https://img.shields.io/badge/Modrinth-00AF5C?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/mod/emote)
[![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/hanhy06/emote)

Want to share your emotes? [Join the Discord server](https://discord.gg/CRWqKbSebW) and share them with the community!

## User Commands

| Command            | Description                                                                        |
|--------------------|------------------------------------------------------------------------------------|
| `/emote`           | Opens the emote menu.                                                              |
| `/emote stop`      | Stops the currently playing emote.                                                 |
| `V`                | Opens the client-side emote wheel. The key can be changed in Minecraft’s controls. |

When the client mod is installed, the wheel's Order button can add, remove, and reorder its entries. The first six shortcuts appear on the first wheel page. Shortcut order is stored per server in the client's `config/emote/wheel-shortcuts.json`; server syncs refresh availability and append newly discovered emotes without changing the existing order.

## Admin Commands

| Command                      | Description                                               |
|------------------------------|-----------------------------------------------------------|
| `/emote list`                | Lists loaded emotes and their source information.         |
| `/emote reload`              | Reloads configuration and animation JSON files.           |
| `/emote enable <id>`         | Enables an emote and reloads the emote list.              |
| `/emote disable <id>`        | Disables an emote and reloads the emote list.             |
| `/emote stop-all`            | Stops every active emote.                                 |
| `/emote stress-test <count>` | Runs an emote stress test and reports server performance. |

## Server Configuration

The following files and directories are created in `config/emote` when the server starts for the first time:

```text
config/emote/
├── config.json
├── emotes.json
└── animations/
```

### `config.json`

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

| Setting                          | Description                                                                            |
|----------------------------------|----------------------------------------------------------------------------------------|
| `menu_page_size`                 | Number of emotes displayed on each menu page.                                          |
| `mineskin_api_key`               | MineSkin API key used to apply player skins to emotes.                                 |
| `mineskin_poll_interval_seconds` | Interval between MineSkin job checks. Must be between `1` and `60` seconds.            |
| `mineskin_cache_retention_days`  | Removes MineSkin cache files unused for this many days. Defaults to `30`.              |
| `mineskin_cache_max_mib`         | Maximum MineSkin disk cache size before the oldest files are removed. Defaults to `256`. |
| `max_active_display_entities`    | Maximum display entities used by active emotes across the server. Playback is rejected before spawning entities when the projected total exceeds this value. `0` disables the limit. Defaults to `512`. |

#### Player Skin Support

To apply the playing player’s skin to compatible emotes, set `mineskin_api_key` in `config/emote/config.json` to an API key from [MineSkin](https://account.mineskin.org/).

Skin parts and their order can be assigned in the web converter. If no API key is configured or MineSkin is unavailable, the textures stored in the animation JSON are used instead.

### `emotes.json`

Controls emote availability and play permissions.

```json
{
  "disabled": ["example:disabled"],
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": ["example:dance", "example:cry"],
      "idle": {
        "delay_seconds": 300,
        "emote": ["example:dance", 70, "example:cry", 30]
      }
    },
    {
      "permission": "emote.default",
      "emotes": ["example:hello", "example:wave"]
    },
    {
      "permission": "emote.admin",
      "emotes": ["*"]
    }
  ]
}
```

- `disabled` contains the exact IDs of emotes that should not be loaded.
- `permissions` preserves the listed order, which determines idle emote selection.
- `permission` is the permission node for the entry. `emote.default` is granted to every player by default.
- `emotes` contains the emotes granted by the permission.
- `idle` is optional. The first matching permission entry with `idle` plays a randomly selected `emote` after `delay_seconds` of inactivity, then repeats at the same interval while the player remains idle. String-only arrays use equal chances. Alternating ID and integer chance arrays use explicit chances that must total `100`. A new candidate is selected after each successful playback without immediately repeating the previous emote when alternatives are available; the remaining chances are normalized automatically.
- `*` grants access to every enabled emote.

Run `/emote reload` after editing the file manually.

## Animation Files

Put `.json` files exported by the converter in `config/emote/animations`:

```text
config/emote/animations/
├── hello.json
├── dance.json
└── sitting/
    ├── sit-down.json
    ├── sit-idle.json
    ├── stand-up.json
    └── sit-sequence.json
```

All `.json` files below `animations`, including files in nested directories, are loaded recursively. File names and directory names are only used for storage. The root `id` field is the identifier used by commands, permissions, enable/disable settings, and the UI.

Animation files may declare `"type": "animation"`. Files without `type` are also treated as animations, so existing converter output remains valid.

Animations intended only as sequence steps can set `"standalone": false` at the root. The default is `true`. Sequence-only animations remain loaded and can be referenced by sequences, but are omitted from the emote menu, wheel, search, and command suggestions. Direct playback by exact ID is also rejected. Administrator listing and enable/disable management still include them.

See [the animation format](https://github.com/hanhy06/emote/blob/main/docs/emote-animation-format.md) and [reference JSON](https://github.com/hanhy06/emote/blob/main/docs/emote-animation-format.json) for details.

Invalid files are skipped independently. If multiple files declare the same `id`, every file sharing that ID is rejected.

Animation JSON files are limited to 8 MiB and timelines to 10 minutes. Node, display, transform, visibility-change, and command counts are controlled by animation creators rather than rejected by the loader. Runtime load is managed with the server-wide `max_active_display_entities` setting when playback starts; this also applies to runtime API registrations.

### Sequence Files

A sequence is a playable emote that runs existing animations in order. Put it anywhere below `config/emote/animations` with `"type": "sequence"`:

```json
{
  "type": "sequence",
  "schema_version": 1,
  "id": "example:sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down, wait, and stand up."
  },
  "player": {
    "hidden": true,
    "stop_conditions": {
      "movement_distance": 0.1,
      "jump": true,
      "submerge": true,
      "ride": true,
      "damage": true,
      "attack": true,
      "game_mode_change": true
    }
  },
  "steps": [
    {"emote": "example:sit_down"},
    {"emote": ["example:sit_idle_1", 30, "example:sit_idle_2", 40, "example:sit_idle_3", 30], "repeat": 3},
    {"emote": "example:stand_up"}
  ]
}
```

- `steps` must contain at least one animation.
- `repeat` is optional and defaults to `1`. It counts complete animation cycles, including cycles of a looping animation.
- `emote` accepts one animation ID, a string-only list with equal chances, or an alternating ID and integer chance list whose chances total `100`. Lists select a random candidate for every repeat, exclude the immediately previous candidate when alternatives are available, and normalize the remaining chances automatically.
- Sequences may reference animations but not other sequences.
- Referenced animations must be loaded and enabled.
- `player` uses the same format as animation files and controls player visibility and stop conditions for the entire sequence.
- A referenced animation's own `player` settings apply when it is played independently, but are ignored while it is part of a sequence.
- Sequences are compiled into one in-memory animation during reload. Their display entities are created once and reused until the whole sequence finishes.
- Referenced animations must use compatible node IDs, node types, display content, and skin layouts. Default transforms and visibility may differ between steps.
- Timeline command events are preserved. Start, loop, and stop command events are not currently supported in animations referenced by a sequence.
- Server-synchronized animations cannot be used in a sequence.
- A sequence appears in commands, permissions, and the emote UI under its own `id`. Stopping or interrupting it cancels all remaining steps.

See the [sequence reference JSON](https://github.com/hanhy06/emote/blob/main/docs/emote-sequence-format.json) for a complete example.

## Mod API

Emote provides a server-side API under `io.github.hanhy06.emote.api`.

Access it through `EmoteApi.getInstance()`.

The API supports playback control, runtime emote registration, state queries, cancellable play listeners, and playback lifecycle listeners.

API mutations must run on the server thread. Runtime registrations survive `/emote reload` and are automatically removed when the server stops.

## Troubleshooting

### An emote does not appear

Run `/emote reload` and check the server log.

An emote may be skipped when:

- its animation JSON is invalid;
- its Minecraft version does not match the server;
- its exact ID is listed in `disabled` in `emotes.json`; or
- another file declares the same ID.

### An emote cannot be played

Check that:

- the emote is granted in `emotes.json`;
- the player has the required permission; and
- the exact `namespace:path` ID is being used.

### The player’s skin is not applied

Confirm that `mineskin_api_key` is configured and check the server log.

If MineSkin is unavailable, the textures stored in the animation JSON are used instead.

### Configuration changes are ignored

Check that the JSON syntax is valid, then run `/emote reload`.

Invalid configuration is rejected and the previously loaded configuration remains active.

## License

Apache License 2.0
