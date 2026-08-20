# Animation Format

Animation files use schema version `3` and define the display entities, transformation matrices, and commands required by one emote.

```json
{
  "type": "animation",
  "schema_version": 3,
  "id": "example:wave",
  "metadata": {
    "name": "Wave",
    "description": "A short wave."
  },
  "settings": {
    "standalone": true,
    "cooldown": "2s",
    "player": {
      "hidden": true,
      "stop_conditions": {
        "movement_distance": 0.1,
        "jump": true,
        "submerge": true,
        "ride": true,
        "damage": true,
        "attack": true,
        "game_mode_change": true
      }
    },
    "playback": {
      "mode": "once",
      "loop_delay": "0t"
    }
  },
  "nodes": {},
  "timeline": {
    "duration": "20t",
    "keyframes": [],
    "events": {}
  }
}
```

A complete Animation reference JSON containing every node and event type is provided separately in the repository's `docs/reference` directory.

## Root fields

| Field | Description |
|---|---|
| `type` | Must be `animation`. |
| `schema_version` | Must be `3`. |
| `id` | A lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and custom metadata. |
| `settings` | Selection visibility, player behavior, and playback settings. |
| `nodes` | Display entities and command anchors keyed by stable node IDs. |
| `timeline` | Duration, transformations, visibility states, and command events. |

Animation JSON files are limited to 8 MiB and timelines are limited to 10 minutes.

!!! tip inline end "Time units"
    Emote uses Minecraft time format.<br>
    `1s` equals `20t`.

    `s`: seconds<br>
    `t` or omitted: ticks<br>
    `d`: Minecraft days

## Metadata

- `name`: Name shown in commands and the emote UI.
- `description`: Description shown to players.
- Other fields are preserved and exposed to the API and web converter.

## Settings

### Selection visibility

`standalone` determines whether an Animation appears in menus, the wheel, searches, and command suggestions and can be played directly by normal players. Set it to `false` for Animations used only inside Sequences. Trusted API calls and players with `emote.bypass` may still play it directly.

### Player behavior

- `hidden`: Hides the original player during playback.
- `movement_distance`: Stops playback after the player moves the specified horizontal distance. `0` disables it.
- `jump`, `submerge`, `ride`, `damage`, `attack`, `game_mode_change`: Stop playback when the corresponding action occurs.

### Playback mode

| Mode | Description |
|---|---|
| `once` | Plays the timeline once. `loop_delay` must be `0t`. |
| `hold` | Plays once, then holds the last frame until stopped. `loop_delay` must be `0t`; unavailable in Sequences. |
| `loop` | Repeats the timeline after `loop_delay`. |
| `server_sync` | Plays in synchronization with server time; unavailable in Sequences. |

## Nodes

Each property name in `nodes` is a stable node ID. Every node must have a `space` and a row-major `default_matrix` containing 16 numbers.

| Space | Root used in two-player playback |
|---|---|
| `scene` | Shared scene root created by the initiating player |
| `initiator` | Initiator placement defined by Sequence `participants` |
| `partner` | Partner placement defined by Sequence `participants` |

All three spaces use the same player root in standalone playback and single-player Sequences. Their distinction matters in two-player Sequences.

| Type | Required fields | Purpose |
|---|---|---|
| `item_display` | `item_stack_snbt`, `item_display` | Displays an item stack |
| `block_display` | `block_state_snbt` | Displays a block state |
| `text_display` | `text` | Displays a Minecraft text component |
| `anchor` | None beyond `type` and `default_matrix` | Provides a command execution position without creating an entity |

Display nodes also support:

- `visible`: Defaults to `true`.
- `entity_nbt`: Additional display-entity SNBT.
- `skin`: Binds a player skin to an item display.

`skin` requires `participant`, a player-body `part`, and a nonnegative `order`. `participant` must be `initiator` or `partner` and must match the node's `space`. `scene` nodes do not support skin binding.

Supported parts are `head`, `body`, `left_arm`, `right_arm`, `left_leg`, and `right_leg`. Nodes bound to the same participant and part receive skin data in `order` order.

## Timeline

`duration` specifies the total playback time. `keyframes` must be sorted by `time`.

A keyframe may contain:

- `node_transforms`: Mapping of node IDs to `matrix` values
- `node_states`: Mapping of display-node IDs to `visible` values
- `interpolation_duration`: Interpolation time used unless overridden by an individual transform

Interpolation cannot extend outside the interval between the node's previous transform and the current keyframe. Anchor nodes support transformations but not visibility states.

## Command events

Optional `timeline.events` supports four event groups.

| Event | Execution time |
|---|---|
| `start` | When playback starts |
| `timeline` | At the specified `time` |
| `loop` | After each repetition completes |
| `stop` | When playback stops |

Each event has `source`, `origin`, and a `commands` array. `source` is `player`, `server`, or a node name. `origin` is the Animation `root` or a node name and may include an optional three-number `offset`.

Timeline events must be sorted by time and occur before the end of the timeline.

## Migrating from schema 1

The web converter can import published schema 1 Animations and export them as schema 3. The server only loads schema 3 directly.

| Schema 1 | Schema 3 |
|---|---|
| No `type` | Requires `type: "animation"` |
| `minecraft_version` | Removed from runtime files |
| `tick_rate: 20` | Removed because Minecraft's 20 TPS is implicit |
| `transform_space` | Removed; matrices remain root-local, 16-number, and row-major |
| Root `standalone` | `settings.standalone` |
| No cooldown | `settings.cooldown`; use `"0t"` when migrating |
| Root `player` | `settings.player` |
| `timeline.loop` | `settings.playback.mode` |
| `timeline.loop_delay_ticks` | `settings.playback.loop_delay` |
