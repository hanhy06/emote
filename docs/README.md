# Emote for Fabric

Emote is a Minecraft Fabric emote player for BD Engine humanoid exports.
It plays emotes from datapacks on the server, and modded clients can replace the datapack skin with the player's current skin.

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/a6e8b74b404bb30dbc06e61a3456fb5b5349ee9d.gif)

## Quick Start

### Included Example Datapacks

The repository already includes ready-to-test example datapacks in [`docs/example`](https://github.com/hanhy06/emote/tree/master/docs/example):

- `emote.cry.zip`
- `emote.hello.zip`
- `emote.no.zip`
- `emote.yes.zip`

To test them as-is:

1. Put the mod jar into the server `mods` folder.
2. Copy the four zip files above into the world `datapacks` folder.
3. Replace `config/emote/pack.json` with this:

```json
{
  "permissions": {
    "": [
      {
        "datapack_identifier": "cry",
        "name": "cry",
        "command_name": "cry",
        "description": "cry",
        "default_animation_name": "default",
        "options": "loop"
      },
      {
        "datapack_identifier": "hello",
        "name": "hello",
        "command_name": "hello",
        "description": "hello",
        "default_animation_name": "default",
        "options": "loop"
      },
      {
        "datapack_identifier": "no",
        "name": "no",
        "command_name": "no",
        "description": "no",
        "default_animation_name": "default",
        "options": "loop"
      },
      {
        "datapack_identifier": "yes",
        "name": "yes",
        "command_name": "yes",
        "description": "yes",
        "default_animation_name": "default",
        "options": "loop"
      }
    ]
  }
}
```

- Start the server or run `/emote reload`.
- Try:

```mcfunction
/emote play hello
/emote play hello default_loop
/emote play yes
/emote menu
```

The example datapacks already contain `play_anim_loop.mcfunction`, so `options: "loop"` immediately adds loop entries such as `default_loop`.

## Key Features

- Server-side emote playback from datapacks
- Automatic datapack discovery and reload support
- `/emote` command
- Optional player skin replacement on modded clients
- Optional MineSkin upload/cache support for baked player skin textures
- `pack.json` metadata-based loop entry generation for datapacks with `play_anim_loop.mcfunction`
- Support for BD Engine humanoid exports that use `emote:*` skin markers

## Install

### Server

1. Put the mod jar into the server `mods` folder.
2. Put the emote datapack zip or folder into the world `datapacks` folder.
3. Register the datapack namespace and metadata in `config/emote/pack.json`.
4. Start the server or run `/emote reload`.
5. Use `/emote`.

### Client

Client installation is optional.

- With the client mod installed, the emote model can use the player's current skin.
- Without the client mod, the skin defined in the datapack is used instead.
- The example datapacks include the developer skin, so their default appearance may look unusual without client skin replacement.

## Datapacks

Emotes are loaded from datapacks whose namespaces are listed in `config/emote/pack.json`.
A datapack is treated as an emote when its `data/<namespace>` folder matches a configured `datapack_identifier`.

### Prepare Your Own Datapack

BD Engine export zips can be prepared with [`prepare_emote_datapack.py`](https://github.com/hanhy06/emote/blob/master/docs/prepare_emote_datapack.py).
The script adds the `emote:*` markers used for player skin support and writes an `emote.name.zip` file.

```powershell
python docs\prepare_emote_datapack.py [--defaults] [--swap-left-right] path\to\project.zip
```

### Animation Requirements

- A normal animation entry requires `data/<namespace>/function/a/<animation>/play_anim.mcfunction`.
- A loop entry additionally requires `data/<namespace>/function/a/<animation>/play_anim_loop.mcfunction`.
- The included example datapacks all provide both `play_anim.mcfunction` and `play_anim_loop.mcfunction` for `default`.

## Config

The mod uses two config files:

- `config/emote/config.json`
- `config/emote/pack.json`

### `config.json`

- `menu_page_size`: number of emotes shown per menu page
- `player_skin_port`: port used by the built-in skin texture endpoint, `0` for automatic selection
- `mineskin_api_key`: optional MineSkin API key for uploading baked player skin textures and using MineSkin-hosted URLs
- `emote_permission`: default permission required to use emotes

### `pack.json`

`pack.json` maps permission strings to emote pack entries.

```json
{
  "permissions": {
    "": [
      {
        "datapack_identifier": "hello",
        "name": "hello",
        "command_name": "hello",
        "description": "hello",
        "default_animation_name": "default",
        "options": "loop"
      }
    ],
    "emote.pack.vip": [
      {
        "datapack_identifier": "wave_pack",
        "name": "wave",
        "command_name": "wave",
        "description": "wave",
        "default_animation_name": "default",
        "options": ""
      }
    ]
  }
}
```

- `permissions` maps a permission string to a list of emote packs
- `datapack_identifier` is the datapack namespace, which means the folder name under `data/<namespace>`
- An empty permission key `""` means the pack has no extra pack-specific permission
- The same `datapack_identifier` cannot appear in more than one permission group
- `command_name`, `name`, `description`, `default_animation_name`, and `options` are defined here instead of being read from the datapack itself

### `options`

`options` is a space-separated string.

- `loop`: adds extra `*_loop` animation entries when the datapack has `play_anim_loop.mcfunction`
- `visible_player`: keeps the real player visible instead of forcing invisibility during playback

Example:

- `""`
- `"loop"`
- `"visible_player loop"`

## License

This project is licensed under the Apache License 2.0.
