# Animation

An Animation file contains the model, movement, playback settings, and command events for one action. This page explains the settings available in the converter and how they affect playback.

For file creation and installation, see [Adding Custom Emotes](../server/custom-emote.md). For direct JSON editing, see the [complete format example](https://github.com/hanhy06/emote/blob/dev/docs/reference/animation.json).

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
    Time settings use Minecraft time format. `1s` equals `20t`.

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

### Display interpolation

`display_interpolation` sets how many ticks the client uses to display changes in a display part's position, rotation, or scale.

| Value | Behavior |
|---|---|
| `0t` | Applies changes immediately. |
| `1t` | Interpolates over 1 tick. Used when the setting is omitted. |
| `2t`, etc. | Interpolates over the specified duration. Negative values are not allowed. |

Timeline evaluation and command events still run on server ticks. This setting is separate from interpolation of the emote's overall position and direction.

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

Select the playback behavior with `settings.playback.mode`.

| Mode | Behavior | Available in Sequences |
|---|---|---|
| `once` | Plays the timeline once. | Yes |
| `hold` | Plays once, then holds the last pose until stopped. | No |
| `loop` | Plays the complete timeline first, then repeats the loop section. | Yes |
| `server_sync` | Plays from the position determined by server time. Playbacks started at different times remain at the same timeline position. | No |

Loop settings belong in the same `playback` object.

| Setting | Behavior |
|---|---|
| `loop_start` | Starting position for the second and subsequent cycles in `loop` mode. Defaults to `0t` and must be earlier than the timeline end. Other modes allow only `0t`. |
| `loop_delay` | Wait between cycles. Defaults to `0t`. Values greater than zero are allowed only in `loop` and `server_sync` modes. |

## Tracks

A track changes a model part's state over time. Each part is called a node, and a node can have several types of tracks.

For example, raising and turning a hand uses position and rotation tracks. Each track contains keyframes that record values at specific times.

| Track | State | Purpose |
|---|---|---|
| `position` | Position | Move a part |
| `rotation` | Rotation | Change a part's orientation |
| `scale` | Scale | Enlarge or shrink a part |
| `visible` | Visibility | Hide or show a part at a specific time |
| `nbt` | Display entity data | Change display content or entity properties |

Position, rotation, and scale can be interpolated between keyframes. `linear` interpolates between two values; `step` holds the current value until the next keyframe. `easing` controls how the speed changes within an interpolated segment.

Visibility and NBT change immediately at the specified time. A property without a track uses the node's initial value.

[Molang](molang.md) can calculate position, rotation, scale, and visibility during playback, or calculate values for NBT keyframes. NBT can change only permitted display data. Fields managed by the playback engine, such as position, transformation, and interpolation, cannot be changed.

## Command events

`timeline.events` runs commands at specific points during playback.

| Event | Execution time |
|---|---|
| `start` | Playback start |
| `timeline` | Specified timeline time |
| `loop` | Cycle completion |
| `stop` | Playback stop |

`timeline` events are ordered by time and may occur anywhere from the timeline start through its end, including the end itself. Commands do not begin with `/`.

### Command source and origin

Set the command source (`source`) and execution position (`origin`) separately.

| Field | Values | Meaning |
|---|---|---|
| `source` | `player`, `server`, `node` | Who runs the command. A `node` source must be a display node. |
| `origin` | `root`, `node` | Runs at the emote's root position or a specific node's position. |
| `origin.offset` | Three-number position offset | Adjusts the execution position. Defaults to `[0, 0, 0]`. |

When `source` or `origin` is `node`, also specify the node ID. Anchor nodes create no entity and can be used as origins, but not as command sources.

## Extensions

### Molang

See the [Molang documentation](molang.md) for initialization and tick programs and dynamic track values.

### Callbacks {#callbacks}

Select callbacks registered by another server mod in `callbacks` to run code at playback start, ticks, loops, and close.

```json
"callbacks": [
  {"name": "example:wave", "payload": "right_hand"}
]
```

`name` is the registered callback ID. `payload` is a string passed to the callback unchanged and defaults to an empty string. For registration, see the [Mod API](api.md#lifecycle-callbacks).
