# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/a6e8b74b404bb30dbc06e61a3456fb5b5349ee9d.gif)

Emote is a Fabric mod that plays humanoid animations created with BD Engine as Minecraft emotes.

The mod is designed with server-side use in mind. Its main feature, emote playback, works when installed only on the server. Installing the mod on the client is optional.

> Don’t judge the mod by the demo—the developer is a programmer, not an animator. I’m looking forward to seeing the much better emotes you create!

## Usage

| Action                | Description                                                                                              |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| `/emote`              | Opens the emote menu.                                                                                    |
| `/emote search`       | Opens the emote search dialog.                                                                           |
| `/emote play <emote>` | Plays an emote by command name or namespace.                                                             |
| `/emote stop`         | Stops the currently playing emote.                                                                       |
| `/emote reload`       | Reloads the configuration and emote datapacks.                                                           |
| V key                 | Opens the emote wheel when the client mod is installed. Release the key toward a slot to play its emote. |

## Adding Emotes

BD Engine datapack ZIP files or folders can be converted with `docs/emote.py` included in the repository.

```powershell
python docs\emote.py path\to\project.zip
```

The script adds Emote metadata and, by default, player skin-part markers to the BD Engine datapack, then creates an `emote.<name>.zip` file. Place the generated file in the world's `datapacks` directory and run `/emote reload` to register it.

The display name, description, and command name can also be specified manually.

```powershell
python docs\emote.py `
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
| `--output-dir`      | Sets the directory for generated ZIP files.                                |

Example emote datapacks are available in `docs/example`.

## Configuration

Configuration files are created automatically in `config/emote` when the server starts for the first time.

### `config.json`

```json
{
  "version": "<mod version>",
  "menu_page_size": 6,
  "mineskin_api_key": "",
  "mineskin_poll_interval_seconds": 3,
  "emote_permission": "emote.use"
}
```

| Setting                          | Description                                                                                                    |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `menu_page_size`                 | Number of emotes displayed on each menu page.                                                                  |
| `mineskin_api_key`               | MineSkin API key used to apply player skins.                                                                   |
| `mineskin_poll_interval_seconds` | Interval in seconds between checks for completion of a MineSkin skin-generation job. Must be between 1 and 60. |
| `emote_permission`               | Base permission required to use emote features.                                                                |

When a [MineSkin API](https://account.mineskin.org/) key is configured, the player's current skin is applied to the head, body, arms, and legs of compatible emotes. Generated skin textures are cached on the server, so the same skin does not need to be processed repeatedly.

MineSkin's current free-tier rate limit is sufficient for typical small servers and private sessions with friends. Since processed skins are cached, the API normally only needs to process a skin when it has not been seen before or has changed.

Without an API key, or if the player's skin cannot be prepared, the default skin included in the datapack is used instead.

### `packs.json`

Emotes can be enabled or disabled by namespace, with an optional additional permission.

```json
{
  "packs": {
    "hello": {
      "enabled": true,
      "permission": ""
    },
    "vip_dance": {
      "enabled": true,
      "permission": "emote.pack.vip"
    },
    "disabled_emote": {
      "enabled": false,
      "permission": ""
    }
  }
}
```

Emotes not listed in this file are enabled by default and do not require an additional permission. Run `/emote reload` after changing the configuration.

## License

Apache License 2.0
