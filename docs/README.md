# Emote for Fabric

Emote is a Minecraft Fabric emote player compatible with BD Engine humanoid exports.
It supports server-side emote playback from datapacks, and if the client also has the mod installed, the emote model can use the player's current skin so it looks more like the player.

![Emote demo](docs/Emote.gif)

---

## Key Features

- Server-side emote playback from datapacks
- Automatic datapack discovery and reload support
- `/emote` command with menu, list, play, stop, and reload actions
- Optional live player skin rendering on modded clients
- Support for BD Engine humanoid exports that use `emote:*` skin markers

---

## Install

### Server

1. Put the mod jar into the server `mods` folder.
2. Put the emote datapack zip or folder into the world `datapacks` folder.
3. Register the datapack namespace and metadata in `config/emote/pack.json`.
4. Start the server or run `/emote reload`.
5. Use `/emote` or `/emote play <emote>`.

### Client

Client installation is optional.

- With the client mod installed, the emote model can use the player's current skin.
- Without the client mod, the skin defined in the datapack is used instead.
- The example datapack includes the developer skin, so unusual skin visuals are expected there.

---

## Commands

- `/emote`  
  Opens the emote menu for the current player.
- `/emote menu [page]`  
  Opens a specific menu page.
- `/emote list`  
  Shows loaded emotes and their basic metadata.
- `/emote play <emote> [animation]`  
  Plays the selected emote.
- `/emote stop`  
  Stops the active emote for the player.
- `/emote reload`  
  Reloads config and emote datapacks.

---

## Datapacks

Emotes are loaded from datapacks.

Datapacks are usually prepared directly from a BD Engine export zip with `prepare_emote_datapack.py`. The tool adds the `emote:*` markers needed for player skin support and writes the result as a `emote.name.zip` file that can be placed in the `datapacks` folder.

The server loads emote name, description, command name, default animation, and per-pack permission grouping from `config/emote/pack.json`.

Namespaces listed in `pack.json` are treated as emotes. A datapack is auto-enabled when its `data/<namespace>` folder matches a configured `datapack_identifier`.

[Usage](https://github.com/hanhy06/emote/blob/master/docs/prepare_emote_datapack.py)

```powershell
python docs\prepare_emote_datapack.py path\to\project.zip
```

---

## Config

The mod uses two config files:

- `config/emote/config.json`
- `config/emote/pack.json`

### `config.json`

- `menu_page_size` sets how many emotes are shown per menu page.
- `player_skin_port` sets the port used for the built-in skin texture endpoint. Use `0` for automatic selection.
- `emote_permission` sets the default permission required to use emotes.

### `pack.json`

`pack.json` stores datapack metadata and extra permissions in this structure:

```json
{
  "version": "dev",
  "permissions": {
    "": [
      {
        "datapack_identifier": "wave_pack",
        "name": "Wave",
        "command_name": "wave",
        "description": "Friendly wave emote",
        "default_animation_name": "default"
      }
    ],
    "emote.pack.vip": [
      {
        "datapack_identifier": "bow_pack",
        "name": "Bow",
        "command_name": "bow",
        "description": "Polite bow emote",
        "default_animation_name": "default"
      }
    ]
  }
}
```

- The `permissions` object maps a permission string to a list of emote packs.
- `datapack_identifier` is the datapack namespace, which means the folder name under `data/<namespace>`.
- An empty permission key `""` means the pack has no extra pack-specific permission.
- The same `datapack_identifier` cannot appear in more than one permission group.
- `command_name`, `name`, `description`, and `default_animation_name` are defined in `pack.json`.

---

## License

This project is licensed under the Apache License 2.0.
