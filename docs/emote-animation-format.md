# Emote animation format

Animation files use schema version 3 and contain the display entities, transforms, and commands for one emote.

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

See [emote-animation-format.json](./emote-animation-format.json) for a complete example containing every node and event form.

## Root fields

| Field | Description |
|-------|-------------|
| `type` | Must be `animation`. |
| `schema_version` | Must be `3`. |
| `id` | Lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and optional custom metadata. |
| `settings` | Selection, player, and playback behavior. |
| `nodes` | Display entities and command anchors keyed by stable node IDs. |
| `timeline` | Duration, transforms, visibility changes, and command events. |

Animation JSON files are limited to 8 MiB and timelines to 10 minutes.

## Time values

Gameplay time values are strings using Minecraft time units:

| Example | Meaning |
|---------|---------|
| `"1d"` | One Minecraft day. |
| `"5s"` | Five seconds. |
| `"20"` / `"20t"` | Twenty ticks. The `t` suffix is optional. |

This format is used by `cooldown`, `loop_delay`, timeline and event `time`, `duration`, and `interpolation_duration`.

## Metadata

- `name` is the name shown in commands and the emote UI.
- `description` is the description shown to players.
- Additional fields are preserved and exposed through the API and web converter.

## Settings

### Selection and cooldown

- `standalone` controls whether the animation appears in the emote menu, wheel, search, and command suggestions and whether it can be played directly. Set it to `false` for an animation intended only for sequences.
- `cooldown` is applied to the player after the animation starts successfully.

### Player behavior

- `hidden` hides the original player while the animation is playing.
- `movement_distance` stops playback after the player moves the given horizontal distance. `0` disables this condition.
- `jump`, `submerge`, `ride`, `damage`, `attack`, and `game_mode_change` stop playback when the corresponding action occurs.

### Playback

| Mode | Description |
|------|-------------|
| `once` | Plays the timeline once. `loop_delay` must be `0t`. |
| `loop` | Repeats the timeline after `loop_delay`. |
| `server_sync` | Synchronizes playback to server time. It cannot be used in a sequence. |

## Nodes

Each property in `nodes` is a stable node ID. Every node requires a `space` and a 16-number `default_matrix` in column-major order.

`space` is `scene`, `initiator`, or `partner`. Scene nodes use the shared scene root. Participant nodes use their participant root from a collaborative sequence.

| Type | Required fields | Purpose |
|------|-----------------|---------|
| `item_display` | `item_stack_snbt`, `item_display` | Displays an item stack. |
| `block_display` | `block_state_snbt` | Displays a block state. |
| `text_display` | `text` | Displays a Minecraft text component. |
| `anchor` | None beyond `type` and `default_matrix` | Provides a command source or origin without creating a display entity. |

Display nodes also support:

- `visible`, which defaults to `true`;
- `entity_nbt`, containing additional display entity SNBT; and
- `skin` on item display nodes, containing `participant`, a player body `part`, and non-negative `order`.

`participant` must match the node's `initiator` or `partner` space. Supported skin parts are `head`, `body`, `left_arm`, `right_arm`, `left_leg`, and `right_leg`. Nodes with the same part are ordered using `order` when the player's skin is applied.

## Timeline

`duration` sets the total playback time. `keyframes` must be ordered by `time`.

A keyframe can contain:

- `node_transforms`, mapping node IDs to a `matrix`;
- `node_states`, mapping display node IDs to a `visible` value; and
- `interpolation_duration`, used by transforms in that keyframe unless a transform overrides it.

An interpolation duration must fit between the node's previous transform and the current keyframe. Anchor nodes support transforms but not visibility states.

## Command events

The optional `timeline.events` object supports four event groups:

| Event | Runs |
|-------|------|
| `start` | When playback starts. |
| `timeline` | At its specified `time`. |
| `loop` | After each completed loop. |
| `stop` | When playback stops. |

Each event contains a `source`, an `origin`, and a `commands` array. A source can be the `player`, the `server`, or a named `node`. An origin can be the animation `root` or a named `node`, with an optional three-number `offset`.

Timeline events must be ordered by time and occur before the timeline duration.
