# Emote for Fabric

Emote is a BD Engine based emote player for Minecraft Fabric.
It is designed around server-side playback, so a server can run emotes from datapacks without requiring every player to install the mod.
If the client also has the mod installed, full dynamic player skin remapping can be applied to body, arm, and leg parts as well.

---

## Key Features

- Server-side emote playback from datapacks
- Automatic datapack discovery and reload support
- `/emote` command with menu, list, play, stop, and reload actions
- Optional dynamic player skin support for `player_head` based rigs
- Support for BD Engine humanoid rigs that use `emote:*` skin markers

---

## Install

### Server

1. Put the mod jar into the server `mods` folder.
2. Put the emote datapack zip or folder into the world `datapacks` folder.
3. Start the server or run `/emote reload`.
4. Use `/emote` or `/emote play <emote>`.

### Client

- Client installation is not required for normal emote playback.
- Install the same mod on the client only if you want dynamic full-body player skin rendering.

---

## Commands

- `/emote`
  - Opens the emote menu for the current player.
- `/emote menu [page]`
  - Opens a specific menu page.
- `/emote list`
  - Shows loaded emotes, command names, default animations, and part counts.
- `/emote play <emote> [animation]`
  - Plays the selected emote.
- `/emote stop`
  - Stops the active emote for the player.
- `/emote reload`
  - Reloads config and emote datapacks.

---

## Datapack Format

An emote datapack should include:

- `pack.mcmeta`
- `emote-datapack.json`
- `data/<namespace>/function/_/create.mcfunction`
  - or `data/<namespace>/functions/_/create.mcfunction`

`emote-datapack.json` is used for display and command metadata:

```json
{
  "name": "Example Emote",
  "description": "Shown in the emote menu.",
  "command_name": "example",
  "default_animation": "default"
}
```

For dynamic player skin support, `minecraft:player_head` parts in `_create.mcfunction` should use `minecraft:profile.name` markers like:

```nbt
components:{"minecraft:profile":{name:"emote:head"}}
components:{"minecraft:profile":{name:"emote:body"}}
components:{"minecraft:profile":{name:"emote:right_arm"}}
components:{"minecraft:profile":{name:"emote:left_arm"}}
components:{"minecraft:profile":{name:"emote:right_leg"}}
components:{"minecraft:profile":{name:"emote:left_leg"}}
```

---

## Config

The main config file is stored at `config/emote/config.json`.

```json
{
  "version": "current mod version",
  "menu_page_size": 6,
  "player_skin_port": 0,
  "emote_permission": "emote.use",
  "emote_permissions": {}
}
```

- `menu_page_size`
  - Number of emotes shown per menu page.
- `player_skin_port`
  - Port used for the built-in skin texture endpoint.
  - `0` means auto-select behavior.
- `emote_permission`
  - Default permission node required to use emotes.
- `emote_permissions`
  - Optional per-emote permission overrides.

The mod also writes an example datapack metadata file to:

- `config/emote/datapack/emote-datapack.example.json`

---

## Skin Support Notes

- Unmodded clients can still see and play the emote itself.
- Full dynamic player skin mapping for body parts requires the client mod.
- The current skin workflow is built for BD Engine humanoid exports that are assembled from `player_head` displays.

---

## Datapack Prep Helper

`prepare_emote_datapack.py` converts a BD Engine datapack `.zip` into a sibling `.emote.zip`.

It can:

- add `name:"emote:*"` markers to `_create.mcfunction`
- create `emote-datapack.json` if it is missing
- write the result as `original-name.emote.zip`

Usage:

```powershell
python docs\prepare_emote_datapack.py path\to\project.zip
```

You can also drag a datapack `.zip` onto the script file.

---

## License

This project is licensed under the Apache License 2.0.
