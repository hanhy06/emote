# Sequence

A Sequence connects multiple Animations into one emote. It can combine repeats, transitions, waits, and random choices.

## Basic example

This example plays sit down → idle action three times → stand up. Each referenced Animation must be installed on the server.

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "sit:idle.sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down and relax."
  },
  "settings": {
    "cooldown": "0t",
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
    }
  },
  "steps": [
    {"emote": "sit:sit_down"},
    {"emote": "sit:idle_sky", "repeat": 3, "transition": "2t"},
    {"emote": "sit:stand_up1"}
  ]
}
```

See the [Sequence example JSON](https://github.com/hanhy06/emote/blob/dev/docs/reference/sequence.json) for the complete format. Sequence files use schema version `4` and are limited to 8 MiB.

!!! tip "Time units"
    Time settings use Minecraft time format. `1s` equals `20t`.

    - `s`: seconds
    - `t` or omitted: ticks
    - `d`: Minecraft days

## Order and repeats

Steps play in the order listed in `steps`. Set `emote` to the Animation ID for each Animation step.

```json
{"emote": "sit:idle_sky", "repeat": 3}
```

`repeat` sets the number of repetitions for that step and defaults to `1`. Each repetition runs one playback cycle of the Animation. A repeating Animation's `loop_delay` applies between cycles.

Steps cannot reference another Sequence or an Animation using `hold` or `server_sync` mode. Animations with `standalone: false` are allowed.

## Transitions

`transition` is the time spent moving from the previous Animation's final pose to the next Animation's initial pose. Position, rotation, and scale of shared display nodes are interpolated linearly.

```json
{"emote": "sit:idle_sky", "transition": "2t"}
```

| Condition | Behavior |
|---|---|
| `transition` omitted | Uses `0t`. |
| First Animation | Skips the transition because there is no previous pose. |
| Repeated step | Applies the transition before each repetition's Animation begins. |
| Preceding wait or `loop_delay` | Holds the previous pose while waiting, then applies the transition. |

Visibility, display data, timeline events, and Molang time change when the transition ends and the next Animation begins.

## Waits

A `wait` step holds the previous pose for the specified duration.

```json
"steps": [
  {"emote": "sit:sit_down"},
  {"wait": "10t"},
  {"emote": "sit:stand_up1"}
]
```

Waits cannot be the first or last step, cannot be consecutive, and cannot use `repeat`.

## Random selection

### Equal probabilities

List multiple IDs in the `emote` array to select one with equal probability.

```json
{
  "emote": [
    "sit:idle_sky",
    "sit:idle_butterfly",
    "sit:idle_flower"
  ],
  "repeat": 3
}
```

### Weights

Alternate IDs and integer weights to set selection probabilities. The weights must total `100`.

```json
{
  "emote": [
    "sit:idle_sky", 40,
    "sit:idle_butterfly", 35,
    "sit:idle_flower", 25
  ],
  "repeat": 3
}
```

A new choice is made for each repetition. When there are multiple Animation candidates, the most recently selected Animation is excluded and the remaining probabilities are recalculated. Repeat-control IDs in the next section do not count as Animation candidates.

## Repeat control

Include these IDs in random selection to skip a repetition or end the current step's repeat loop.

| ID | Behavior |
|---|---|
| `emote:continue` | Consumes one repetition and selects the next without adding an Animation or `loop_delay`. |
| `emote:break` | Ends the current step's repeat loop and advances to the next step. Does not end the entire Sequence. |

```json
{
  "emote": [
    "sit:idle_sky", 50,
    "sit:idle_butterfly", 30,
    "emote:continue", 15,
    "emote:break", 5
  ],
  "repeat": 10
}
```

`emote:continue` may be selected consecutively. A Sequence must have at least one real Animation candidate.

## Common settings

| Field | Description |
|---|---|
| `id` | The Sequence's identifier, in lowercase `namespace:path` form. |
| `metadata.name` | Name shown in commands and the emote menu. |
| `metadata.description` | Description shown to players. |
| Additional metadata | Information such as the creator or license. |
| `settings.cooldown` | Cooldown applied after the Sequence ends successfully. Individual Animation cooldowns are not added. |
| `settings.player` | Player visibility and stop conditions for the entire Sequence. Replaces each Animation's player settings. |
| `target_minecraft_version` | The converter's output target version. Does not constrain the server version or guarantee compatibility of referenced Animations. |

See the [Animation documentation](animation.md) for individual player settings.

Sequences are directly playable and do not define `standalone`, `rotation_deadzone`, or `playback`. Rotation tracking and display interpolation use the currently playing Animation's settings.

## Animation compatibility

All referenced Animations must be loaded successfully on the server. Compatibility is checked when emotes are reloaded.

Regular node IDs may differ between Animations. The Sequence creates the required nodes once and reuses them until playback ends. Nodes absent from the current Animation are hidden.

### Shared node IDs

| Must match | May differ |
|---|---|
| Node type | Position, rotation, and scale |
| Item, item display context, block state, text, and entity NBT | Initial visibility |
| Player skin binding | Timeline tracks |

### Player skins

All Animations must have the same compiled player-skin layout. Skin node IDs and body regions derived from `part`, `order`, and local Y scale must match.

Repeat-control IDs are excluded from compatibility checks.

## Events and callbacks

Command events defined in each Animation also run within a Sequence.

| Event | Execution time within the Sequence |
|---|---|
| `start` | Animation segment start |
| `timeline` | Specified time within that Animation |
| `loop` | Loop segment completion |
| `stop` | Animation segment completion, or interruption of the Sequence while that segment is playing |

At a shared boundary, the previous Animation's stop events run before the next Animation's start events.

Sequence `callbacks` use the same format as [Animation callbacks](animation.md#callbacks). The Sequence and each Animation segment have separate callback contexts and state. Each Animation segment also has its own Molang session.

## Playback behavior

Random choices and repeat controls are resolved before playback starts, and the selected steps are compiled into one playback.

Stopping the Sequence cancels all remaining steps.
