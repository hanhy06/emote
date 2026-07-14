# Emote

> Use the [web converter](https://hanhy06.github.io/emote/) to convert a BD Engine project into an emote datapack.

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
| `/emote play <emote>` | Plays an emote by command name or namespace.                                       |
| `/emote stop`         | Stops the currently playing emote.                                                 |
| `V`                   | Opens the client-side emote wheel. The key can be changed in Minecraft’s controls. |

## Admin Commands

| Command                      | Description                                       |
|------------------------------|---------------------------------------------------|
| `/emote stop-all`            | Stops every active or pending emote.              |
| `/emote enable <namespace>`  | Enables an emote and reloads the emote list.      |
| `/emote disable <namespace>` | Disables an emote and stops its active instances. |
| `/emote reload`              | Reloads the configuration and emote datapacks.    |

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
    "example_disabled"
  ],
  "permissions": {
    "default": [
      "hello",
      "yes",
      "no"
    ],
    "emote.vip": [
      "dance",
      "cry"
    ],
    "emote.admin": [
      "*"
    ]
  }
}
```

- `disabled` contains namespaces that should not be loaded.
- `default` contains emotes available to every player.
- Other keys are permission nodes that grant additional emotes.
- `*` grants access to every enabled emote.

Run `/emote reload` after editing the file manually.

## Emote Datapack Structure

Each emote namespace must contain the following files:

```text
pack.mcmeta
data/<namespace>/emote.json
data/<namespace>/function/_/create.mcfunction
data/<namespace>/function/<entrypoint>.mcfunction
```

`emote.json` defines the display name, description, command name, playback function, and player visibility.

Namespaces with invalid metadata or missing required files are not registered.

## Player Skin Support

Configure a [MineSkin](https://account.mineskin.org/) API key to apply the playing player’s skin to the head, body, arms, and legs of compatible emotes.

If no API key is configured or skin preparation fails, the textures stored in the datapack are used instead. Generated textures and pending jobs are cached on the server.

To mark a skin part, set the profile’s top-level `name` field on a `minecraft:player_head` item display.

```snbt
item:{
  id:"minecraft:player_head",
  components:{
    "minecraft:profile":{
      name:"emote:left_arm0",
      properties:[...]
    }
  }
}
```

The following markers are supported:

| Part      | Marker            |
|-----------|-------------------|
| Head      | `emote:head`      |
| Body      | `emote:body`      |
| Left arm  | `emote:left_arm`  |
| Right arm | `emote:right_arm` |
| Left leg  | `emote:left_leg`  |
| Right leg | `emote:right_leg` |

A number starting from `0` can be appended to any marker to define the order of multiple pieces.

```text
emote:left_arm0
emote:left_arm1
```

Lower numbers identify pieces closer to the center of the body. When ordering a part, every piece of that part must include a number.

Player heads without a valid marker keep the texture stored in the datapack.

## Troubleshooting

### An emote does not appear

Run `/emote reload` and check the server log.

An emote may be skipped when:

- `emote.json` is invalid;
- a required function is missing;
- its namespace is disabled in `packs.json`; or
- its namespace or command name conflicts with another emote.

### The player’s skin is not applied

Confirm that `mineskin_api_key` is configured and that the datapack contains valid `emote:*` markers.

If MineSkin is unavailable or skin preparation fails, the textures included in the datapack are used.

### Configuration changes are ignored

Check that the JSON syntax is valid, then run `/emote reload`. Invalid configuration is rejected and the currently loaded configuration remains active.

## License

Apache License 2.0
