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
    "rotation_deadzone": 50,
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

| Field                 | Description                                                          |
|-----------------------|----------------------------------------------------------------------|
| `type`                | Must be `animation`.                                                 |
| `schema_version`      | Must be `4`.                                                         |
| `id`                  | A lowercase Minecraft identifier in `namespace:path` form.           |
| `metadata`            | Display name, description, and custom metadata.                      |
| `settings`            | Selection visibility, player behavior, and playback settings.        |
| [`molang`](molang.md) | Optional initialization and per-tick Molang programs.                |
| `nodes`               | A nonempty map of display and anchor nodes keyed by stable node IDs. |
| `timeline`            | Duration, node tracks, and command events.                           |

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
- Additional fields can include whatever you want, such as licenses or creators.

## Settings

### Selection visibility

`standalone` determines whether an Animation appears in menus, the wheel, searches, and command suggestions and can be played directly by normal players. Set it to `false` for Animations used only inside Sequences.

### Cooldown and rotation

- `cooldown`: Nonnegative playback cooldown. It starts only after playback begins successfully.
- `rotation_deadzone`: Finite angle from `0` to `180` degrees. During standalone playback and partner offer/wait states, the display root follows the initiator's yaw only when the difference exceeds this angle. `0` follows every yaw change without display rotation interpolation; positive values use three ticks of rotation interpolation, and `180` keeps the initial orientation. The interpolation setting updates with the active Animation step in a Sequence.

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
The node must inherit `initiator` or `partner` space; participant-hand items are not allowed in `scene` space.

Anchor nodes do not support `visible` or `entity_nbt`. They can have transform tracks, but not visibility tracks, and cannot be used as a command source because they have no entity.

### Player skin binding

`skin` requires a player-body `part` and a nonnegative `order`. `participant` defaults to `initiator` and may be `initiator` or `partner`; it must match the node's inherited space. `scene` nodes do not support skin binding.

Supported parts are `head`, `body`, `left_arm`, `right_arm`, `left_leg`, and `right_leg`. Nodes bound to the same participant and part receive skin data in `order` order.

## Timeline tracks

`timeline.duration` is the positive total playback time. `timeline.tracks` maps node IDs to any combination of `position`, `rotation`, `scale`, `visible`, and `nbt` tracks. A node may omit tracks entirely; an omitted channel uses the node's initial value.

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
- Every vector value contains three finite numbers or [Molang](molang.md) strings.
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

The value may also be a [Molang](molang.md) string; zero is hidden and any other finite result is visible.

### NBT tracks

NBT tracks apply stepped display-entity data changes. A `value` may be compound SNBT or a Molang-selected set of compound SNBT options. The selected compound is merged with the state produced by the preceding keyframes.

```json
"nbt": [
  {
    "time": "0t",
    "value": {
      "select": "math.random_integer(0, 3)",
      "options": [
        "{item:{id:'minecraft:poppy',count:1},Glowing:false}",
        "{item:{id:'minecraft:dandelion',count:1},Glowing:false}",
        "{item:{id:'minecraft:blue_orchid',count:1},Glowing:false}"
      ]
    }
  },
  {"time": "10t", "value": "{Glowing:true}"}
]
```

- The first keyframe must be at `0t`; times must be strictly increasing and cannot exceed the timeline duration.
- Keyframes support only `time` and `value`; NBT changes are stepped and cannot declare interpolation.
- A selected value contains a nonblank Molang `select` expression and at least two `options`. There is no fixed upper option limit beyond the 8 MiB Animation file limit.
- `select` must produce a finite integer index from `0` through `options.length - 1`. Any other result stops playback as a runtime failure.
- A selector is evaluated once when its keyframe is first applied. Starting from a later synchronized tick evaluates each preceding keyframe once in order, and a new loop or Sequence segment selects again.
- Fields added after the `0t` keyframe are rejected. Declare every field the track may modify in the first keyframe.
- Every option at `0t` must declare the same top-level fields so later keyframes have one stable state shape.
- Runtime-owned fields such as identity, position, transformation, interpolation, and passengers cannot be modified.
- Anchor nodes do not support NBT tracks. A node displaying a participant's held item may use an NBT track, but the track cannot replace its `item` field.

## Events

Optional `timeline.events` supports four event groups.

| Event | Execution time |
|---|---|
| `start` | When playback starts. |
| `timeline` | At the specified `time`. |
| `loop` | After each repetition completes. |
| `stop` | When playback stops. |

Each event contains object-shaped `source` and `origin` fields, a `commands` array, and an optional `callbacks` array. Commands do not start with `/`. Callback names are namespaced identifiers; `payload` is an optional string passed through unchanged to the registered mod listener.

```json
{
  "source": {"type": "server"},
  "origin": {
    "type": "node",
    "node": "effect_anchor",
    "offset": [0, 0.5, 0]
  },
  "commands": ["particle minecraft:flame ~ ~ ~ 0 0 0 0 1 normal"],
  "callbacks": [
    {"name": "example:sword_swing", "payload": "right_hand"}
  ]
}
```

`source.type` may be `player`, `server`, or `node`. A node source also requires `node` and must reference a display node. `origin.type` may be `root` or `node`; a node origin requires `node`. Every origin may include an optional three-number `offset`, which defaults to zero.

Timeline events must be ordered by time and occur before the end of the timeline.

Named callbacks are dispatched to server-side listeners registered through `EmoteApi.addCallbackListener`. An unregistered name is ignored with a warning; one failing listener does not interrupt playback or the remaining listeners.

Callback events identify both the top-level playback and the Animation that declared the callback. `playbackId` is the requested Animation or Sequence ID, while `animationId` is the currently executing Animation ID. `playbackTick` and `animationTick` provide the corresponding timeline positions, and `phase` is `START`, `TIMELINE`, `LOOP`, or `STOP`. For standalone playback, both IDs and both ticks refer to the same Animation.

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

The converter can migrate ordinary schema 3 files automatically. Schema 4 runtime data is preserved when exported again. The web converter bakes deterministic parented nodes, independent tracks, Molang, easing, and discontinuous `pre`/`post` values for preview. If a runtime value cannot be evaluated safely, export remains available and the preview falls back to the Create pose.
