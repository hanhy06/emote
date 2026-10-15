# Animation

An Animation file contains the model, movement, playback settings, and command events for one action. Animation files use schema version `5`.

For file creation and installation, see [Adding Custom Emotes](../server/custom-emote.md). For direct JSON editing, see the [Animation example](https://github.com/hanhy06/emote/blob/dev/docs/reference/animation.json).

The root `nodes` object defines the model. The `animation` object contains its duration, playback mode, Molang, tracks, and events. Root `settings` controls direct selection, cooldown, rotation tracking, and player behavior; root `callbacks` selects registered lifecycle callbacks.

## Basic information

| Field | Description |
|---|---|
| `id` | The emote's identifier, in lowercase `namespace:path` form. |
| `metadata.name` | Name shown in commands and the emote menu. Cannot be empty. |
| `metadata.description` | Description shown to players. |
| Additional metadata | Information such as the creator or license. |
| `target_minecraft_version` | The converter's output target version. The server does not use it to accept, reject, or migrate the file. |

Animation files are limited to 8 MiB, and timelines are limited to 10 minutes.

!!! tip "Time units"
    Time fields use Minecraft time strings. `"1s"` equals `"20t"`; a value without a suffix must still be a string, such as `"20"`.

    - `s`: seconds
    - `t` or omitted: ticks
    - `d`: Minecraft days

## Playback settings

### Direct selection and cooldown

| Setting | Behavior |
|---|---|
| `standalone` | Determines whether normal players can select and play this Animation directly. When `false`, it is excluded from menus, the wheel, searches, and command suggestions. Use `false` for actions intended only for Sequences. |
| `cooldown` | Cooldown applied after playback ends successfully. Must be `0t` or greater. |

### Rotation tracking

`rotation_deadzone` sets how many degrees the player's direction must differ from the emote's before the emote turns to follow. Values range from `0` to `180` degrees.

| Value | Behavior |
|---|---|
| `0` | Follows every change in the player's direction without rotation interpolation. |
| Greater than `0`, less than `180` | Turns when the direction difference exceeds the specified angle. Rotation is interpolated over 3 ticks. |
| `180` | Keeps the direction from the start of playback. |

### Player visibility and stop conditions

Set `settings.player.hidden` to `true` to hide the original player during playback.

`settings.player.stop_conditions` determines which actions stop playback.

| Setting | Stop condition |
|---|---|
| `movement_distance` | Moving the specified horizontal distance. `0` disables stopping on movement. |
| `jump` | Jumping |
| `submerge` | Submerging in water |
| `ride` | Riding |
| `damage` | Taking damage |
| `attack` | Attacking |
| `game_mode_change` | Changing game mode |

For conditions other than distance, `true` stops playback when the action occurs.

### Playback mode

Select the playback behavior with `animation.playback.mode`.

| Mode | Behavior | Available in Sequences |
|---|---|---|
| `once` | Plays the timeline once. | Yes |
| `hold` | Plays once, then holds the last pose until stopped. | No |
| `loop` | Plays the complete timeline first, then repeats the loop section. | Yes |
| `server_sync` | Plays from the position determined by server time. Playbacks started at different times remain at the same timeline position. | No |

Delays and loop settings belong in the same `animation.playback` object.

| Setting | Behavior |
|---|---|
| `start_delay` | Wait before the Animation begins. Defaults to `"0t"`. |
| `loop_start` | Starting position for the second and subsequent cycles in `loop` mode. Defaults to `0t` and must be earlier than the timeline end. Other modes allow only `0t`. |
| `loop_delay` | Wait between cycles. Defaults to `0t`. Applies only in `loop` and `server_sync` modes; ignored in `once` and `hold` modes. |

Delays can also use Molang; see [Animation time and delays](molang.md#animation-time-and-delays).

## Model and tracks

### Nodes and attachments

Each entry in `nodes` is a transform node. Its optional `parent` places it in a hierarchy, and its `transform` array applies operations in the listed order. An operation has its own `id`, an `op`, and an initial `value`.

Operations include `translate`, `rotate_euler`, `rotate_quaternion`, `scale`, and `matrix`. Euler rotations also specify an axis `order`, such as `XYZ`.

A node's `attachments` object defines what appears at that transform. Rendered attachment types are `item_display`, `block_display`, `text_display`, and `player_skin`. A node may have multiple attachments or none. A node without an entity attachment still provides a position for events and callbacks.

Player-skin attachments use `part` and a `region` with `from` and `to` values. The converter writes these regions from the skin-part assignments; they are also used when checking Sequence compatibility.

### Track targets and drivers

Each entry in `animation.tracks` selects a `target`, a `channel`, and a `driver`. `target.operation` identifies an operation by its ID, not by its operation type. `target.attachment` identifies an attachment within the selected node.

| Channel | Target | Behavior |
|---|---|---|
| `value` | `node` and `operation` | Changes that transform operation's value. |
| `visible` | `node`, optionally with `attachment` | Changes visibility of the node or a single attachment. |
| `nbt` | `node` and a display `attachment` | Applies display-data patches. |

| Driver | Behavior |
|---|---|
| `curve` | Interpolates transform values using `keys` and `segments`. |
| `expression` | Calculates transform or visibility values directly, including Molang expressions. |
| `state` | Changes visibility or applies NBT patches at the specified key times. |

A property without a track uses the node, operation, or attachment's initial value. [Molang](molang.md) can provide dynamic values within these drivers.

### Curves and pose interpolation

A curve has one `segments` entry between each pair of keys. A key uses either `value`, or separate `pre` and `post` values for the incoming and outgoing sides of the key. By default, sampling exactly at a key uses its `post` value; `driver.at_key` can change that policy.

Segment interpolation supports `step`, `linear`, `catmull_rom`, `hermite`, and `bezier` for three-component values. Quaternion values use `step` or `slerp`; matrix values use `step`. Segment `easing` controls progress through the interpolation and can preserve the source format's easing behavior.

### NBT patches

An NBT state key contains `value.merge`, a compound SNBT string or Molang expression, and an optional `value.remove` list of top-level field names. Patches are applied in order. They can change display content and entity properties, but cannot change fields owned by the playback engine, such as position, transformation, and interpolation. See [Dynamic NBT values](molang.md#dynamic-nbt-values) for expressions.

## Command events

`animation.events` runs commands at specific points during playback.

| Event | Execution time |
|---|---|
| `start` | Playback start |
| `timeline` | Specified timeline time |
| `loop` | Cycle completion |
| `stop` | Playback stop |

Timeline events include a time string and a `direction`: `forward` by default, `backward`, or `both`. They may occur anywhere from the timeline start through its end, including the end itself. Lifecycle events do not have a time or direction.

Commands belong in `action.commands` with `action.type` set to `commands`. They run with game master permission. Review imported commands before installing an Animation. External actions in converted files cannot currently run on the server.

### Command source and origin

Set the command source (`source`) and execution position (`origin`) separately.

| Field | Values | Meaning |
|---|---|---|
| `source.type` | `player`, `server`, `node` | Who runs the command. A `node` source must select an entity attachment. |
| `origin.type` | `root`, `node` | Runs at the emote's root position or a specific node's position. |
| `origin.offset` | Three-number position offset | Adjusts the execution position. Defaults to `[0, 0, 0]`. |
| `origin.rotation` | `origin` | Uses the selected origin's rotation for command execution. Omit it to retain the command source's direction. |

When `source.type` or `origin.type` is `node`, also specify the node ID. A node source also requires `source.attachment`. Nodes without entity attachments can be used as origins, but not as command sources.

## Callbacks {#callbacks}

Select callbacks registered by another server mod in `callbacks` to run code at playback start, ticks, loops, and close.

```json
"callbacks": [
  {"name": "example:wave", "payload": "right_hand"}
]
```

`name` is the registered callback ID. `payload` is a string passed to the callback unchanged and defaults to an empty string. For registration, see the [Mod API](api.md#lifecycle-callbacks).
