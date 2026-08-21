# Animation

Animation files use schema version `4`. An Animation defines a hierarchy of display and anchor nodes, their local transforms, independently timed animation tracks, and optional command events.

```json
{
  "type": "animation",
  "schema_version": 4,
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
  "nodes": {
    "body": {
      "type": "anchor",
      "space": "initiator",
      "transform": {
        "position": [0, 0, 0],
        "rotation": [0, 0, 0],
        "scale": [1, 1, 1]
      }
    },
    "hand": {
      "type": "item_display",
      "parent": "body",
      "item_stack_snbt": "{id:\"minecraft:stick\",count:1}",
      "item_display": "fixed",
      "transform": {
        "position": [0.3, 1.2, 0],
        "rotation": [0, 0, 0],
        "scale": [1, 1, 1]
      }
    }
  },
  "timeline": {
    "duration": "20t",
    "tracks": {
      "hand": {
        "rotation": [
          {
            "time": "0t",
            "value": [0, 0, -20],
            "interpolation": "linear",
            "easing": "ease_in_out_sine"
          },
          {"time": "20t", "value": [0, 0, 20]}
        ]
      }
    },
    "events": {}
  }
}
```

A complete [Animation reference JSON](https://github.com/hanhy06/emote/blob/dev/docs/reference/animation.json) contains every node and event type.

## Root fields

| Field | Description |
|---|---|
| `type` | Must be `animation`. |
| `schema_version` | Must be `4`. |
| `id` | A lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and custom metadata. |
| `settings` | Selection visibility, player behavior, and playback settings. |
| `molang` | Optional initialization and per-tick Molang programs. |
| `nodes` | A nonempty map of display and anchor nodes keyed by stable node IDs. |
| `timeline` | Duration, node tracks, and command events. |

Animation JSON files are limited to 8 MiB and timelines are limited to 10 minutes.

!!! tip inline end "Time units"
    Emote uses Minecraft time format.<br>
    `1s` equals `20t`.

    `s`: seconds<br>
    `t` or omitted: ticks<br>
    `d`: Minecraft days

## Metadata

- `name`: Nonempty name shown in commands and the emote UI.
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
| `server_sync` | Selects the current timeline position from server time so independently started playbacks remain synchronized; unavailable in Sequences. |

## Nodes

Each property name in `nodes` is a stable node ID. Every node requires `type` and `transform`.

```json
"transform": {
  "position": [0, 1.5, 0],
  "rotation": [0, 0, 0],
  "scale": [1, 1, 1]
}
```

`position`, `rotation`, and `scale` each contain three finite numbers. Position and scale use the node's local coordinate system. Rotation is expressed as XYZ Euler angles in degrees.

### Hierarchy and spaces

A root node has no `parent` and must declare one `space`:

| Space | Root used in two-player playback |
|---|---|
| `scene` | Shared scene root created by the initiating player |
| `initiator` | Initiator placement defined by Sequence `participants` |
| `partner` | Partner placement defined by Sequence `participants` |

All three spaces use the same player root in standalone playback and single-player Sequences. Their distinction matters in two-player Sequences.

A child node declares `parent` instead of `space`. It inherits its root node's space, and its local transform is composed after the parent's transform. A parent must exist in the same file, and parent relationships cannot form a cycle.

```json
"child": {
  "type": "anchor",
  "parent": "root",
  "transform": {
    "position": [0, 1, 0],
    "rotation": [0, 0, 0],
    "scale": [1, 1, 1]
  }
}
```

### Node types

| Type | Required fields | Purpose |
|---|---|---|
| `item_display` | Exactly one of `item_stack_snbt` or `item_source`, plus `item_display` | Displays a fixed or participant-held item stack. |
| `block_display` | `block_state_snbt` | Displays a block state. |
| `text_display` | `text` | Displays a Minecraft text component. |
| `anchor` | None beyond the common hierarchy and transform fields | Groups child nodes or provides a command origin without creating an entity. |

Display nodes also support:

- `visible`: Initial visibility; defaults to `true`.
- `entity_nbt`: Additional display-entity compound SNBT. Runtime-owned identity, position, transformation, interpolation, and display-content fields cannot be overridden.
- `skin`: Player-skin binding for an item display.

`item_display` accepts Minecraft item display contexts such as `none`, `fixed`, `head`, `ground`, `gui`, and the first- or third-person hand contexts.

`item_stack_snbt` contains a fixed item stack. Alternatively, `item_source` can display the item currently held in a participant's physical hand:

```json
"item_source": {
  "type": "participant_hand",
  "arm": "right"
}
```

`arm` is the physical `left` or `right` hand, independent of the participant's main-hand setting. Participant-hand items cannot use `skin`.

Anchor nodes do not support `visible` or `entity_nbt`. They can have transform tracks, but not visibility tracks, and cannot be used as a command source because they have no entity.

### Player skin binding

`skin` requires a player-body `part` and a nonnegative `order`. `participant` defaults to `initiator` and may be `initiator` or `partner`; it must match the node's inherited space. `scene` nodes do not support skin binding.

Supported parts are `head`, `body`, `left_arm`, `right_arm`, `left_leg`, and `right_leg`. Nodes bound to the same participant and part receive skin data in `order` order.

## Timeline tracks

`timeline.duration` is the positive total playback time. `timeline.tracks` maps node IDs to any combination of `position`, `rotation`, `scale`, and `visible` tracks. A node may omit tracks entirely; an omitted channel uses the node's `transform` or initial `visible` value.

Position, rotation, and scale are independent vector tracks. Each is an array of keyframes:

```json
"position": [
  {
    "time": "0t",
    "value": [0, 0, 0],
    "interpolation": "linear"
  },
  {"time": "10t", "value": [0, 1, 0]}
]
```

Within each track:

- The first keyframe must be at `0t`.
- Times must be strictly increasing and cannot exceed the timeline duration.
- Every vector value contains three finite numbers or Molang strings.
- `interpolation` belongs to the segment from the current keyframe to the next and defaults to `linear`.
- The final keyframe cannot declare `interpolation` or `easing` because no segment follows it.

`step` holds the current value until the next keyframe. `linear` interpolates position and scale component by component and interpolates rotation with quaternion spherical interpolation.

### Easing

Linear segments may add `easing`. It defaults to `linear` and cannot be combined with `step`.

Supported families are `sine`, `quad`, `cubic`, `quart`, `quint`, `expo`, `circ`, `back`, `elastic`, and `bounce`. Each family supports `ease_in_*`, `ease_out_*`, and `ease_in_out_*`; for example, `ease_in_sine`, `ease_out_cubic`, and `ease_in_out_bounce`.

### Discontinuous vector keyframes

A vector keyframe normally uses one `value` for both its incoming and outgoing value. To create a discontinuity at the keyframe, replace `value` with both `pre` and `post`:

```json
{
  "time": "10t",
  "pre": [0, 1, 0],
  "post": [0, 2, 0],
  "interpolation": "linear"
}
```

The preceding segment ends at `pre`; the following segment begins at `post`. A keyframe must define either `value`, or both `pre` and `post`.

### Visibility tracks

Visibility tracks are stepped boolean states and do not support `interpolation`, `easing`, `pre`, or `post`.

```json
"visible": [
  {"time": "0t", "value": false},
  {"time": "10t", "value": true}
]
```

The value may also be a Molang string; zero is hidden and any other finite result is visible.

## Molang

Any component of a position, rotation, or scale value may be a Molang string. Animation-level programs are optional:

```json
"molang": {
  "initialize": "v.speed = 2;",
  "tick": "v.phase = v.phase + q.delta_time * v.speed;"
}
```

- `initialize` runs when a playback cycle begins.
- `tick` runs before track values are evaluated on each animation tick.
- `v.*` variables persist within that cycle. A new Molang session is created for each repeated cycle and each Animation segment in a Sequence.
- `t.*` temporary variables exist only for one expression evaluation.
- `server_sync` Animations cannot use `molang.tick` because their timeline may begin at an arbitrary synchronized time.

Available queries are:

| Query | Value |
|---|---|
| `q.anim_time` | Current timeline time in seconds. |
| `q.anim_time_ticks` | Current timeline time in ticks. |
| `q.anim_length` | Timeline duration in seconds. |
| `q.delta_time` | `0.05` during normal ticks and `0` when a cycle is initialized. |
| `q.loop_count` | Zero-based number of completed loops. |
| `q.key_frame_lerp_time` | Uneased progress from the current vector keyframe to the next, from `0` to `1`; `0` outside vector evaluation. |
| `q.life_time` | Alias of `q.anim_time`, for imported Molang compatibility. |
| `q.target_x_rotation`, `q.target_y_rotation` | Initiator look pitch and head yaw relative to the body, in degrees. |
| `q.body_x_rotation`, `q.body_y_rotation` | Initiator pitch and absolute body yaw, in degrees. |
| `q.head_x_rotation`, `q.head_y_rotation` | Initiator pitch and absolute head yaw, in degrees. |
| `q.eye_target_x_rotation`, `q.eye_target_y_rotation` | Initiator eye pitch and absolute head yaw, in degrees. |
| `q.ground_speed` | Initiator horizontal movement speed in blocks per second. |
| `q.vertical_speed` | Initiator vertical movement speed in blocks per second; positive is upward. |
| `q.modified_distance_moved` | Initiator walk-animation position used by imported Bedrock movement formulas. |
| `q.walk_distance` | Initiator accumulated movement distance. |
| `q.is_moving` | `1` while the initiator has non-zero movement, otherwise `0`. |
| `q.is_on_ground` | `1` while the initiator is on the ground, otherwise `0`. |
| `q.is_sneaking` | `1` while the initiator is crouching, otherwise `0`. |
| `q.is_sprinting` | `1` while the initiator is sprinting, otherwise `0`. |
| `q.is_swimming` | `1` while the initiator is swimming, otherwise `0`. |
| `q.is_gliding` | `1` while the initiator is gliding with an elytra, otherwise `0`. |
| `q.is_riding` | `1` while the initiator is riding another entity, otherwise `0`. |
| `q.is_using_item` | `1` while the initiator is using an item, otherwise `0`. |
| `q.is_sleeping` | `1` while the initiator is sleeping, otherwise `0`. |
| `q.is_emoting` | `1` during player-backed emote playback, otherwise `0`. |
| `q.item_is_charged` | `1` while the initiator's main-hand crossbow is charged, otherwise `0`. |
| `q.sleep_rotation` | Yaw of the bed occupied by the initiator, or `0` while not sleeping. |
| `q.is_on_fire` | `1` while the initiator is on fire, otherwise `0`. |
| `q.is_in_water` | `1` while the initiator is in water, otherwise `0`. |

Player-state queries always refer to the initiator, including partner Animations. Synthetic stress-test playback has no initiator and evaluates these queries as `0`.

Molang source is compiled when the Animation loads. A value that evaluates to a non-finite number stops playback as a runtime failure.

## Command events

Optional `timeline.events` supports four event groups.

| Event | Execution time |
|---|---|
| `start` | When playback starts. |
| `timeline` | At the specified `time`. |
| `loop` | After each repetition completes. |
| `stop` | When playback stops. |

Each event contains object-shaped `source` and `origin` fields and a `commands` array. Commands do not start with `/`.

```json
{
  "source": {"type": "server"},
  "origin": {
    "type": "node",
    "node": "effect_anchor",
    "offset": [0, 0.5, 0]
  },
  "commands": ["particle minecraft:flame ~ ~ ~ 0 0 0 0 1 normal"]
}
```

`source.type` may be `player`, `server`, or `node`. A node source also requires `node` and must reference a display node. `origin.type` may be `root` or `node`; a node origin requires `node`. Every origin may include an optional three-number `offset`, which defaults to zero.

Timeline events must be ordered by time and occur before the end of the timeline.

## Migrating older Animations

The web converter can import published schema 1 and schema 3 Animations and export schema 4. The server only loads schema 4 directly.

The major schema 3 to 4 changes are:

| Schema 3 | Schema 4 |
|---|---|
| `default_matrix` with 16 row-major values | `transform` with local `position`, `rotation`, and `scale` vectors |
| Every node declares `space` | Root nodes declare `space`; child nodes declare `parent` |
| One sorted `timeline.keyframes` array | Independent `position`, `rotation`, `scale`, and `visible` tracks per node |
| Transform `matrix` values | Vector `value`, or `pre` and `post` values |
| `interpolation_duration` | Segment interpolation is determined by adjacent track times |
| Linear matrix interpolation | Step or eased linear vector interpolation, with quaternion rotation interpolation |
| No dynamic values | Molang programs and Molang track components |

The converter can migrate ordinary schema 3 files automatically. When a schema 4 file uses parented nodes, independently timed transform channels, Molang, easing, or discontinuous `pre`/`post` values, the web preview is limited to the Create pose. These advanced fields are preserved when the file is exported again.
