# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/15c895aea280b546764a0b7f2db2a4cb1f9628c8.gif)

> Special thanks to [Popular Vibe](https://block-display.com/bd/77774) for allowing me to use their animation!

Emote is a server-side emote player that uses Minecraft display entities to play animations created with BD Engine and Animated Java.

The mod can be installed on the server only. Installing it on the client also adds an emote wheel and automatic third-person view while an emote is playing.

Compatible emotes can use the playing player’s skin. The web converter exports BD Engine projects, BD Engine datapacks, and Animated Java blueprints as Emote animation JSON files.

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
  "disabled": [
    "example:disabled"
  ],
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": [
        "example:dance",
        "example:cry"
      ],
      "idle": {
        "delay_seconds": 600,
        "emote": [
          "example:dance",
          "example:cry"
        ]
      }
    },
    {
      "permission": "emote.default",
      "emotes": [
        "example:hello",
        "example:yes",
        "example:no"
      ]
    },
    {
      "permission": "emote.admin",
      "emotes": [
        "*"
      ]
    }
  ]
}
```

- `disabled` contains the exact IDs of emotes that should not be loaded.
- `permissions` preserves the listed order, which determines idle emote selection.
- `permission` is the permission node for the entry. `emote.default` is granted to every player by default.
- `emotes` contains the emotes granted by the permission.
- `idle` is optional. The first matching permission entry with `idle` plays a randomly selected `emote` after `delay_seconds` of inactivity, then repeats at the same interval while the player remains idle. A new candidate is selected after each successful playback without immediately repeating the previous emote when alternatives are available.
- `*` grants access to every enabled emote.

Run `/emote reload` after editing the file manually.

## Animation Files

Put `.json` files exported by the converter in `config/emote/animations`:

```text
config/emote/animations/
├── hello.json
├── dance.json
└── another-emote.json
```

The file name is only used for storage. The root `id` field is the identifier used by commands, permissions, enable/disable settings, and the UI.

See [the animation format](./emote-animation-format.md) and [reference JSON](./emote-animation-format.json) for details.

Invalid files are skipped independently. If multiple files declare the same `id`, every file sharing that ID is rejected.

Animation JSON files are limited to 8 MiB and timelines to 10 minutes. Node, display, transform, visibility-change, and command counts are controlled by animation creators rather than rejected by the loader. Runtime load is managed with the server-wide `max_active_display_entities` setting when playback starts; this also applies to runtime API registrations.

Player visibility and player-driven stop conditions are declared together in each animation's `player` block:

```json
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
}
```

`movement_distance` is the horizontal distance in blocks from the playback start position. Set it to `0` to allow movement. Each boolean independently controls whether that player action stops playback. Manual stops and lifecycle cleanup such as disconnects, reloads, and server shutdown remain unconditional.

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
