# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/a6e8b74b404bb30dbc06e61a3456fb5b5349ee9d.gif)

Emote is a server-side emote player that uses Minecraft display entities to play animations created with BD Engine and Animated Java.

The mod can be installed on the server only. Installing it on the client also adds an emote wheel and automatic third-person view while an emote is playing.

The web converter exports BD Engine projects and Animated Java blueprints as Emote animation JSON files.

> Don’t judge the mod by the demo—the developer is a programmer, not an animator. I’m looking forward to seeing the much better emotes you create!

- [Web converter](https://hanhy06.github.io/emote/)
- [Modrinth](https://modrinth.com/mod/emote)
- [GitHub](https://github.com/hanhy06/emote)

## User Commands

| Command | Description |
|---|---|
| `/emote` | Opens the emote menu. |
| `/emote search` | Opens the emote search dialog. |
| `/emote list` | Lists registered emotes. |
| `/emote play <id>` | Plays the emote with the exact `namespace:path` ID. |
| `/emote stop` | Stops the currently playing emote. |
| `V` | Opens the client-side emote wheel. The key can be changed in Minecraft’s controls. |

## Admin Commands

| Command | Description |
|---|---|
| `/emote stop-all` | Stops every active emote. |
| `/emote enable <id>` | Enables an emote and reloads the emote list. |
| `/emote disable <id>` | Disables an emote and stops its active instances. |
| `/emote reload` | Reloads configuration and animation JSON files. |

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
  "mineskin_poll_interval_seconds": 3
}
```

| Setting | Description |
|---|---|
| `menu_page_size` | Number of emotes displayed on each menu page. |
| `mineskin_api_key` | MineSkin API key used to apply player skins to emotes. |
| `mineskin_poll_interval_seconds` | Interval between MineSkin job checks. Must be between `1` and `60` seconds. |

### `emotes.json`

Controls emote availability and play permissions.

```json
{
  "disabled": [
    "example:disabled"
  ],
  "permissions": {
    "emote.default": [
      "example:hello",
      "example:yes",
      "example:no"
    ],
    "emote.vip": [
      "example:dance",
      "example:cry"
    ],
    "emote.admin": [
      "*"
    ]
  }
}
```

- `disabled` contains the exact IDs of emotes that should not be loaded.
- `emote.default` contains emotes available to every player.
- Other keys are permission nodes that grant access to additional emotes.
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

## Player Skin Support

Configure a [MineSkin](https://account.mineskin.org/) API key to apply the playing player’s skin to compatible emotes.

Skin parts and their order can be assigned in the web converter. If no API key is configured or MineSkin is unavailable, the textures stored in the animation JSON are used instead.

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
