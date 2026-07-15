# Emote

> Use the converter to export a BD Engine project as an emote animation JSON file.

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/a6e8b74b404bb30dbc06e61a3456fb5b5349ee9d.gif)

Emote is a Fabric mod that turns humanoid animations created with BD Engine into multiplayer Minecraft emotes.

The mod can run entirely on the server. Installing it on the client is optional and adds an emote wheel, automatic third-person view, and localized UI.

> Don’t judge the mod by the demo—the developer is a programmer, not an animator. I’m looking forward to seeing the much better emotes you create!

- [Modrinth](https://modrinth.com/mod/emote)
- [GitHub](https://github.com/hanhy06/emote)

## User Commands

| Command               | Description                                                                        |
|-----------------------|------------------------------------------------------------------------------------|
| `/emote`              | Opens the emote menu.                                                              |
| `/emote search`       | Opens the emote search dialog.                                                     |
| `/emote list`         | Lists registered emotes.                                                           |
| `/emote play <id>`    | Plays an emote by its exact `namespace:path` ID.                                  |
| `/emote stop`         | Stops the currently playing emote.                                                 |
| `V`                   | Opens the client-side emote wheel. The key can be changed in Minecraft’s controls. |

## Admin Commands

| Command                      | Description                                       |
|------------------------------|---------------------------------------------------|
| `/emote stop-all`     | Stops every active emote.                          |
| `/emote enable <id>` | Enables an emote and reloads the emote list.       |
| `/emote disable <id>`| Disables an emote and stops its active instances.  |
| `/emote reload`      | Reloads configuration and animation JSON files.   |

## Server Configuration

Configuration files are created in `config/emote` when the server starts for the first time.

### `config.json`

```json
{
  "schema_version": 1,
  "menu_page_size": 6,
  "mineskin_api_key": "",
  "mineskin_poll_interval_seconds": 3
}
```

| Setting                          | Description                                                                 |
|----------------------------------|-----------------------------------------------------------------------------|
| `menu_page_size`                 | Number of emotes displayed on each menu page.                               |
| `mineskin_api_key`               | MineSkin API key used to apply player skins to emotes.                      |
| `mineskin_poll_interval_seconds` | Interval between MineSkin job checks. Must be between `1` and `60` seconds. |

### `packs.json`

Controls emote availability and play permissions.

```json
{
  "disabled": [
    "example:disabled"
  ],
  "permissions": {
    "default": [
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

- `disabled` contains exact animation IDs that should not be loaded.
- `default` contains emotes available to every player.
- Other keys are permission nodes that grant additional emotes.
- `*` grants access to every enabled emote.

Run `/emote reload` after editing the file manually.

## Animation Files

Put one or more `.json` files directly in `config/emote/animations`:

```text
config/emote/animations/
├── hello.json
├── dance.json
└── another-emote.json
```

The file name is only for storage. The root `id` field is the sole registration, command, permission, enable/disable, UI, and network identifier. See [the animation format](./emote-animation-format.md) and [reference JSON](./emote-animation-format.json).

Unknown metadata fields are ignored. Invalid files are skipped independently, and every file sharing a duplicate `id` is rejected.

## Player Skin Support

Configure a [MineSkin](https://account.mineskin.org/) API key to apply the playing player’s skin to the head, body, arms, and legs of compatible emotes.

If no API key is configured, the textures stored in the JSON item stacks are used instead. Generated textures and pending jobs are cached on the server.

To mark a skin part, add `skin.part` and `skin.order` to a `minecraft:player_head` item display node.

```json
{
  "type": "item_display",
  "item_stack_snbt": "{id:\"minecraft:player_head\",count:1}",
  "skin": {
    "part": "left_arm",
    "order": 0
  }
}
```

The following part values are supported:

| Part      | Value       |
|-----------|-------------|
| Head      | `head`      |
| Body      | `body`      |
| Left arm  | `left_arm`  |
| Right arm | `right_arm` |
| Left leg  | `left_leg`  |
| Right leg | `right_leg` |

A number starting from `0` can be appended to any marker to define the order of multiple pieces.

```text
`order: 0`
`order: 1`
```

Lower numbers identify pieces closer to the center of the body. When ordering a part, every piece of that part must include a number.

Player heads without a valid `skin` object keep the texture stored in the JSON.

## Troubleshooting

### An emote does not appear

Run `/emote reload` and check the server log.

An emote may be skipped when:

- its animation JSON is invalid or targets another Minecraft version;
- its exact ID is disabled in `packs.json`; or
- another file declares the same ID.

### The player’s skin is not applied

Confirm that `mineskin_api_key` is configured and that compatible item nodes contain valid `skin` objects.

If MineSkin is unavailable, the textures included in the animation JSON are used.

### Configuration changes are ignored

Check that the JSON syntax is valid, then run `/emote reload`. Invalid configuration is rejected and the currently loaded configuration remains active.

## License

Apache License 2.0
