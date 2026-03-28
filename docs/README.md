# Emote for Fabric

Emote is a Minecraft Fabric emote player compatible with BD Engine humanoid exports.
It supports server-side emote playback from datapacks, and if the client also has the mod installed, the emote model can use the player's current skin so it looks more like the player.

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
3. Start the server or run `/emote reload`.
4. Use `/emote` or `/emote play <emote>`.

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

Datapacks are usually prepared directly from a BD Engine export zip with `prepare_emote_datapack.py`. The tool generates the required metadata, adds the `emote:*` markers needed for player skin support, and writes the result as a `.emote.zip` file that can be placed in the `datapacks` folder.

Usage:

```powershell
python docs\prepare_emote_datapack.py path\to\project.zip
```

---

## Config

The main config file is stored at `config/emote/config.json`.

- `menu_page_size` sets how many emotes are shown per menu page.
- `player_skin_port` sets the port used for the built-in skin texture endpoint. Use `0` for automatic selection.
- `emote_permission` sets the default permission required to use emotes.
- `emote_permissions` allows per-emote permission overrides.

The mod also writes an example datapack metadata file to `config/emote/datapack/emote-datapack.example.json`.

---

## License

This project is licensed under the Apache License 2.0.
