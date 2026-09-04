# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/15c895aea280b546764a0b7f2db2a4cb1f9628c8.gif)

> Thanks to [Popular Vibe](https://block-display.com/bd/77774) for allowing us to use their animation!

[![Web converter](https://img.shields.io/badge/Web_converter-0067C0?style=flat-square&logo=githubpages&logoColor=white)](https://hanhy06.github.io/emote/converter/)
[![Wiki](https://img.shields.io/badge/Wiki-9d4edd?style=flat-square&logo=materialformkdocs&logoColor=white)](https://hanhy06.github.io/emote/)
[![Modrinth](https://img.shields.io/badge/Modrinth-00AF5C?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/mod/emote)
[![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/hanhy06/emote)
[![Discord](https://img.shields.io/badge/Discord-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/CRWqKbSebW)

Join the Discord server to share emotes you have made.

## Features

Emote is a server-side emote mod that plays animations with Minecraft display entities. All server features work when the mod is installed only on the server. Installing it on the client is optional and adds an emote wheel and automatic third-person view during playback.

The web converter supports BD Engine, GeckoLib, and Animated Java. Configure skin parts, metadata, playback settings, and commands without editing Animation JSON.

On the server, LuckPerms permissions can assign emotes and idle emotes per player. Sequences can connect multiple animations or coordinate two players in a collaborative emote, with each player's skin applied to compatible animations. A server API is also available for other mods to register emotes, control playback, and receive events.

## Commands

### Player

| Command            | Description                   |
|--------------------|-------------------------------|
| `/emote`           | Opens the emote menu.         |
| `/emote play <id>` | Plays an emote by ID.         |
| `V`                | Opens the client emote wheel. |

Use the wheel's Edit Wheel button to add, remove, or reorder entries. The order is stored on the client separately for each server.

### Administration

| Command                                       | Description                                                                                                                                                                                   |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/emote list`                                 | Lists loaded emotes with their IDs, durations, and availability.                                                                                                                              |
| `/emote reload`                               | Reloads configuration and animations.                                                                                                                                                         |
| `/emote enable/disable <id>`                  | Enables or disables an emote.                                                                                                                                                                 |
| `/emote stop <player>`, `/emote stop-all`     | Stops one player's emote or all emotes.                                                                                                                                                       |
| `/emote stress-test <time> [count] [packets]` | Plays multiple emotes for the required duration and encodes each packet through a configurable fanout (default 20) to measure server performance. Supports time units such as `10s` and `2m`. |

Administrative commands use the `emote.manage` permission and are granted to game master operators by default.

## Server management

```text
config/emote/
├── config.json
├── emotes.json
├── emote/
└── resource-pack/
```
The directory and configuration files are created automatically on first startup, with sample emotes included under `emote/`.

Place JSON exported by the converter under `emote/`. Subdirectories are loaded as well, and emotes use the `id` in the JSON rather than the filename. Invalid files are skipped individually, while every file sharing a duplicate ID is rejected.

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

Set `mineskin_api_key` to apply player skins.

### `emotes.json`

```json
{
  "schema_version": 3,
  "disabled": ["example:disabled"],
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": ["example:(dance|cry)"],
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

`disabled` turns off emotes, while `permissions` determines the emotes and idle emotes available to each player. Valid emote IDs in `emotes` are matched literally; other entries are Java regular expressions matched against the complete emote ID. `*` is a special value that grants every enabled emote. Regular-expression backslashes must also be escaped for JSON. Every player receives `emote.default`. `emote.bypass` is an administrator and development override that ignores `standalone`, disabled IDs, permissions, and cooldowns.

## Web converter

[Emote Converter](https://hanhy06.github.io/emote/converter/) converts and configures projects without requiring direct edits to Animation JSON. All processing happens locally in the browser.

For a step-by-step guide to converting and installing your own emotes, see [Adding Custom Emotes](https://hanhy06.github.io/emote/server/custom-emote/).

Use the 3D preview to assign skin parts and coordinate spaces, then configure metadata, playback behavior, stop conditions, and frame commands.

![Open a project](https://cdn.modrinth.com/data/qUF0jygw/images/69f77ef2095909af8e7dd5830e452c3b9c4d61b2.png)

![Review](https://cdn.modrinth.com/data/qUF0jygw/images/8f099b4088dca06f6f5f2eb92a77a1d83267c8bb.png)

![Settings](https://cdn.modrinth.com/data/qUF0jygw/images/7b83454fe2ddff3e73abe2fc7f858cceb33ebff6.png)

### Animation conversion

The web converter recalculates the source animation's easing and interpolation curves for Minecraft ticks. It preserves important points in Bézier, Catmull-Rom, bounce, and elastic motion, then selects the keyframe placement with the lowest position, rotation, and scale error to keep the result as close to the original movement as possible.

Each animation can define a cooldown, player visibility, stop conditions such as movement, jumping, attacking, and taking damage, and frame commands.

- [Animation format](https://hanhy06.github.io/emote/developers/animation/)

### Sequence

Connect short animation clips in order and combine waits, weighted random choices, and repeats to create a single emote.

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "example:sit",
  "steps": [
    {"emote": "example:sit_down"},
    {"wait": "10t"},
    {
      "emote": [
        "example:sit_idle_1", 45,
        "example:sit_idle_2", 45,
        "emote:break", 10
      ],
      "repeat": 3
    },
    {"emote": "example:stand_up"}
  ]
}
```

- [Sequence format](https://hanhy06.github.io/emote/developers/sequence/)

#### Collaborative emotes

Combine animations for two players in a sequence to create a collaborative emote. Nearby players facing each other are connected, then the matched or timeout branch is played. Symmetrical motion is automatically mirrored for the other player, while separate `initiator` and `partner` nodes can create asymmetric performances with different motion and skins.

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "emote:handshake",
  "participants": {
    "initiator": {"position": "~ ~ ~", "rotation": "~ 0"},
    "partner": {"position": "^ ^ ^1.2", "rotation": "~180 0"}
  },
  "steps": [{
    "await_partner": {"emote": "emote:handshake_offer", "timeout": "10s"},
    "matched": [
      {"emote": "emote:handshake", "repeat": 2},
      {"wait": "1s"},
      {"emote": "emote:handshake_close"}
    ],
    "timeout": [{"emote": "emote:handshake_close"}]
  }]
}
```

## Mod API

`EmoteApi.getInstance()` provides playback control, runtime registration, state queries, cancellable play listeners, playback lifecycle listeners, and named animation callback listeners. State changes must run on the server thread, and runtime registrations survive reloads.

## Troubleshooting

| Problem                              | Check                                                                                                                                                                                              |
|--------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| An emote does not appear             | Check the `/emote reload` result, server log, duplicate IDs, `disabled`, and whether the animation is sequence-only.                                                                               |
| A player skin is not applied         | Check the converter's skin part assignments and `mineskin_api_key`. Run the emote again after a new skin finishes processing. If MineSkin is unavailable, the animation's default texture is used. |
| A player skin is applied incorrectly | Reassign each node's skin part and order in the web converter. For two-player animations, also check the `initiator` and `partner` coordinate spaces.                                              |

If the problem is not covered here, report it on [Discord](https://discord.gg/CRWqKbSebW) or [GitHub Issues](https://github.com/hanhy06/emote/issues).

## License

This project is distributed under the [Apache License 2.0](https://github.com/hanhy06/emote/blob/main/LICENSE).
