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

## User commands

| Command            | Description                                                                        |
|--------------------|------------------------------------------------------------------------------------|
| `/emote`           | Opens the emote menu.                                                              |
| `/emote stop`      | Stops the currently playing emote.                                                 |
| `V`                | Opens the client-side emote wheel. The key can be changed in Minecraft’s controls. |

When the client mod is installed, the wheel's Order button can add, remove, and reorder its entries. The first six shortcuts appear on the first wheel page. Shortcut order is stored per server in the client's `config/emote/wheel-shortcuts.json`; server syncs refresh availability and append newly discovered emotes without changing the existing order.

## Admin commands

| Command                      | Description                                               |
|------------------------------|-----------------------------------------------------------|
| `/emote list`                | Lists loaded emotes and their source information.         |
| `/emote reload`              | Reloads configuration and animation JSON files.           |
| `/emote enable <id>`         | Enables an emote and reloads the emote list.              |
| `/emote disable <id>`        | Disables an emote and reloads the emote list.             |
| `/emote stop-all`            | Stops every active emote.                                 |
| `/emote stress-test <count>` | Runs an emote stress test and reports server performance. |

## Server configuration

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

#### Player skin support

To apply the playing player’s skin to compatible emotes, set `mineskin_api_key` in `config/emote/config.json` to an API key from [MineSkin](https://account.mineskin.org/).

Skin parts and their order can be assigned in the web converter. If no API key is configured or MineSkin is unavailable, the textures stored in the animation JSON are used instead.

### `emotes.json`

Controls emote availability and play permissions.

```json
{
  "schema_version": 2,
  "disabled": ["example:disabled"],
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": ["example:dance", "example:cry"],
      "idle": {
        "delay": "300s",
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

- `disabled` contains exact IDs blocked by the server access policy. Disabled emotes remain loaded so administrators can inspect them and players with `emote.bypass` can still use them.
- `permissions` preserves the listed order, which determines idle emote selection.
- `permission` is the permission node for the entry. `emote.default` is granted to every player by default.
- `emotes` contains the emotes granted by the permission.
- `idle` is optional. The first matching permission entry with `idle` plays a randomly selected `emote` after `delay`, then repeats at the same interval while the player remains idle. Time values are strings parsed with Minecraft units (`d`, `s`, `t`, or bare ticks). String-only arrays use equal chances. Alternating ID and integer chance arrays use explicit chances that must total `100`. A new candidate is selected after each successful playback without immediately repeating the previous emote when alternatives are available; the remaining chances are normalized automatically.
- `*` grants access to every enabled emote.

`emote.manage` grants every administrative `/emote` subcommand, including reload, list, enable/disable, stop-all, and stress-test. It defaults to game-master operators. `emote.bypass` defaults to false and ignores disabled IDs, play permission rules, and cooldowns; it does not bypass sequence-only restrictions, invalid files, runtime safety limits, or cancellations from other mods.

Run `/emote reload` after editing the file manually.

## Animation and sequence files

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

All `.json` files below `animations`, including files in nested directories, are loaded recursively. File and directory names are only used for organization. Commands, permissions, enable/disable settings, and the UI use the root `id` field as the identifier.

Animations and sequences use `"schema_version": 3`. Names and descriptions are stored in `metadata`, while playback behavior is stored in `settings`. Time values accept Minecraft time units (`d`, `s`, `t`, or bare ticks).

Invalid files are skipped independently. If multiple files declare the same `id`, every file sharing that ID is rejected.

Schema 3 replaces the previously released schema 1 format. It reorganizes animation settings, replaces integer tick fields with Minecraft time strings, adds sequence waits and cooldowns, and introduces participant-aware animations and two-player sequences. The server only loads schema 3 animation and sequence files. The web converter can import schema 1 files and exports them as schema 3.

### Animations

Animations use `"type": "animation"`. Every node can declare `space` as `scene`, `initiator`, or `partner`. A skinned item node also declares whether its skin belongs to the `initiator` or `partner`; that participant must match the node space.

Animations intended only as sequence steps set `"settings": { "standalone": false, ... }`. Sequence-only animations remain loaded and can be referenced by sequences, but are omitted from the emote menu, wheel, search, and command suggestions. Direct playback by exact ID is also rejected. Administrator listing and enable/disable management still include them.

See [the animation format](https://github.com/hanhy06/emote/blob/main/docs/emote-animation-format.md) and [reference JSON](https://github.com/hanhy06/emote/blob/main/docs/emote-animation-format.json) for details.

### Sequences

A sequence uses `"type": "sequence"` to play existing animations in order:

```json
{
  "type": "sequence",
  "schema_version": 3,
  "id": "example:sit",
  "steps": [
    {"emote": "example:sit_down"},
    {"wait": "10t"},
    {"emote": "example:sit_idle", "repeat": 2},
    {"emote": "example:stand_up"}
  ]
}
```

- `emote` selects an animation. It can also be a list for random selection.
- `wait` adds a delay between animation steps.
- `repeat` repeats an animation step and defaults to `1`.
- `emote:continue` skips one repeat, while `emote:break` ends the current repeat loop and continues with the next sequence step.
- Sequences cannot reference other sequences or server-synchronized animations.
- Referenced animations must use compatible nodes, displays, and skin layouts.
- Sequence player settings apply to the entire sequence and replace the referenced animations' player settings.
- Timeline commands are preserved, but start, loop, and stop commands are not supported within a sequence.

Sequences can also coordinate two players. A collaborative sequence plays an offer animation while waiting for a nearby player to start the same sequence, then follows either its matched or timeout branch. Participant-relative node spaces let the animation place and skin each player independently; animations containing only initiator nodes are mirrored automatically for the partner.

See [the sequence format](https://github.com/hanhy06/emote/blob/main/docs/emote-sequence-format.md) for detailed rules, the [single-player reference JSON](https://github.com/hanhy06/emote/blob/main/docs/emote-sequence-format.json), and the [two-player reference JSON](https://github.com/hanhy06/emote/blob/main/docs/emote-two-player-sequence-format.json).

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
