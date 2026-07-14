# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/a6e8b74b404bb30dbc06e61a3456fb5b5349ee9d.gif)

Emote is a Fabric mod that turns humanoid animations created with BD Engine into multiplayer Minecraft emotes.

The mod is designed around server-side playback. Vanilla clients can browse and play emotes without installing the mod. The optional client installation adds an emote wheel, automatic third-person view, and localized client UI.

> Don’t judge the mod by the demo—the developer is a programmer, not an animator. I’m looking forward to seeing the much better emotes you create!

## Usage

| Action                       | Permission  | Description                                                                                              |
| ---------------------------- | ----------- | -------------------------------------------------------------------------------------------------------- |
| `/emote`                     | None        | Opens the emote menu.                                                                                    |
| `/emote search`              | None        | Opens the emote search dialog.                                                                           |
| `/emote list`                | None        | Lists registered emotes and their internal details.                                                      |
| `/emote play <emote>`        | Per-emote   | Plays an emote by command name or namespace.                                                             |
| `/emote stop`                | None        | Stops the currently playing emote.                                                                       |
| `/emote stop-all`            | Game master | Stops every active or pending emote.                                                                     |
| `/emote enable <namespace>`  | Game master | Enables an emote namespace in `packs.json` and reloads emotes.                                           |
| `/emote disable <namespace>` | Game master | Disables an emote namespace, stops its active instances, and reloads emotes.                             |
| `/emote reload`              | Admin       | Reloads the configuration and emote datapacks.                                                           |
| V key                        | Client mod  | Opens the emote wheel. Release the key toward a slot to play its emote.                                  |

Basic emote commands do not require permission. An individual emote can require a permission through `packs.json`.

### Playback behavior

Only one emote can be active per player. Starting another emote replaces the current one. Playback stops when the player:

- moves away from the starting position;
- takes damage;
- attacks another entity;
- mounts an entity;
- dies, changes dimension, or disconnects; or
- is affected by a datapack reload or an operator stop command.

An emote cannot start while the player is already riding an entity. When an emote hides the player, held items are also hidden from observers without changing the player's actual inventory.

The optional client mod switches the player to third-person view during playback and restores the previous camera afterward. Its default emote-wheel binding is V and can be changed in Minecraft's controls menu.

## Adding Emotes

BD Engine datapack ZIP files or folders can be converted with `tools/emote_converter/emote.py` included in the repository.

```powershell
python tools\emote_converter\emote.py path\to\project.zip
```

The script adds Emote metadata and, by default, player skin-part markers to the BD Engine datapack, then creates an `emote.<name>.zip` file. It also normalizes BDEngine's internal function and entity-tag prefix to the datapack namespace. Player heads that are not part of the detected humanoid are left unchanged. Place the generated file in the world's `datapacks` directory and run `/emote reload` to register it.

If one BDEngine project contains multiple `a/<animation>/play_anim_loop` functions, the script splits them into isolated emote namespaces such as `<namespace>_1`, `<namespace>_2`, and `<namespace>_3`. Without explicit names, the generated namespaces are also used as display names and command names. `--name` and `--command-name` are used as bases with numeric suffixes when supplied.

Multiple emotes can be combined into one datapack. Each input must use a different namespace and command name.

```powershell
python tools\emote_converter\emote.py `
  --bundle-name basic `
  path\to\wave.zip `
  path\to\bow.zip
```

This creates `emote.basic.zip`. Each emote keeps its own namespace and stores its metadata at `data/<namespace>/emote.json`.

### How datapacks are read

The mod scans every ZIP or folder in the world's `datapacks` directory. A namespace is registered as one emote when it contains all of the following:

```text
pack.mcmeta
data/<namespace>/emote.json
data/<namespace>/function/_/create.mcfunction
data/<namespace>/function/<entrypoint>.mcfunction
```

`emote.json` supplies the display name, description, command name, entrypoint, and player visibility setting. Invalid or disabled namespaces are skipped. When a BDEngine project contains multiple animations under `function/a`, the conversion script creates an isolated namespace and `emote.json` for each animation.

### Marking player skin parts manually

To apply the playing player's skin manually, use a `minecraft:player_head` item display in `create.mcfunction` and put a skin-part marker in the profile's top-level `name` field. Keep this field before `properties`.

```snbt
item:{
  id:"minecraft:player_head",
  components:{
    "minecraft:profile":{
      name:"emote:head",
      properties:[...]
    }
  }
}
```

Valid markers are `emote:head`, `emote:body`, `emote:left_arm`, `emote:right_arm`, `emote:left_leg`, and `emote:right_leg`. If one limb uses multiple player heads, give every piece the same limb marker. Player heads without a valid marker keep the texture stored in the datapack.

The display name, description, and command name can also be specified manually.

```powershell
python tools\emote_converter\emote.py `
  --name "Hello" `
  --description "Wave hello." `
  --command-name hello `
  path\to\project.zip
```

| Option              | Description                                                                |
| ------------------- | -------------------------------------------------------------------------- |
| `--name`            | Sets the name shown in the menu and emote wheel.                           |
| `--description`     | Sets the emote description.                                                |
| `--command-name`    | Sets the name used by `/emote play`.                                       |
| `--entrypoint`      | Sets the function path to play. The default is `a/default/play_anim_loop`. |
| `--show-player`     | Keeps the actual player visible during playback.                           |
| `--metadata-only`   | Adds metadata without applying the player's skin to the emote.              |
| `--swap-left-right` | Swaps the automatically detected left and right body parts.                |
| `--bundle-name`     | Combines all inputs into one `emote.<name>.zip` datapack.                   |
| `--output-dir`      | Sets the directory for generated ZIP files.                                |

Example emote datapacks are available in `docs/example`.

## Configuration

Configuration files are created automatically in `config/emote` when the server starts for the first time.

### `config.json`

```json
{
  "schema_version": 1,
  "menu_page_size": 6,
  "mineskin_api_key": "",
  "mineskin_poll_interval_seconds": 3
}
```

| Setting                          | Description                                                                                                    |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `menu_page_size`                 | Number of emotes displayed on each menu page.                                                                  |
| `mineskin_api_key`               | MineSkin API key used to apply player skins.                                                                   |
| `mineskin_poll_interval_seconds` | Interval in seconds between checks for completion of a MineSkin skin-generation job. Must be between 1 and 60. |

When a [MineSkin API](https://account.mineskin.org/) key is configured, the player's current skin is applied to the head, body, arms, and legs of compatible emotes. Generated skin textures and pending jobs are cached on the server, so the same skin does not need to be processed repeatedly.

Without an API key, or if the player's skin cannot be prepared, the default skin included in the datapack is used instead.

### `packs.json`

Emotes can be enabled or disabled by namespace. Play permissions define which namespaces each player can use.

```json
{
  "packs": {
    "hello": {
      "enabled": true
    },
    "vip_dance": {
      "enabled": true
    },
    "disabled_emote": {
      "enabled": false
    }
  },
  "permissions": {
    "default": [
      "hello"
    ],
    "emote.pack.vip": [
      "vip_dance"
    ],
    "emote.pack.admin": [
      "*"
    ]
  }
}
```

`default` lists the emotes available to every player. Other keys are permission nodes, and a player can use the combined namespaces granted by all of their permissions. `*` grants every enabled emote. An emote that is not granted by `default`, a matching permission, or `*` cannot be played. A disabled emote cannot be played regardless of permission.

Run `/emote reload` after editing the file manually. `/emote enable` and `/emote disable` only update the namespace's enabled state and preserve all permission groups.

## Troubleshooting

### An emote does not appear

Run `/emote reload` and check the server log. A namespace is skipped when its metadata is invalid, required functions are missing, it is disabled, or its namespace or command name conflicts with another emote.

### The emote wheel opens the regular menu

The wheel has not received emote data from the server. Confirm that the mod is installed on both the server and client and that their Minecraft versions match. The server-driven menu remains available as a fallback.

### The player's skin is not applied

Confirm that `mineskin_api_key` is configured and that the datapack contains valid `emote:*` skin-part markers. If MineSkin is unavailable or skin preparation fails, playback uses the textures included in the datapack.

### Configuration changes are ignored

Check that both JSON files are valid, then run `/emote reload`. Invalid values are rejected and the currently loaded configuration remains active.

## License

Apache License 2.0
