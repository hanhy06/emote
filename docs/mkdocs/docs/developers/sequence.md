# Sequence

Sequence files use schema version `4` and combine existing Animations into one emote.

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "sit:idle.sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down and relax.",
    "author": "@soji2318"
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
    {
      "emote": [
        "sit:idle_sky", 40,
        "sit:idle_butterfly", 35,
        "sit:idle_flower", 25
      ],
      "transition": "2t",
      "repeat": 2
    },
    {"emote": ["sit:stand_up1", 60, "sit:stand_up2", 40]}
  ]
}
```

Complete example: [linear Sequence](https://github.com/hanhy06/emote/blob/dev/docs/reference/sequence.json).

## Root fields

| Field | Description |
|---|---|
| `type` | Must be `sequence`. |
| `schema_version` | Must be `4`. |
| `target_minecraft_version` | Optional converter output target, such as `26.3`. Reference information only; it does not constrain the server version or guarantee compatibility of referenced animations. |
| `id` | A lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and custom metadata. |
| `settings` | Cooldown and player behavior for the entire Sequence. |
| `steps` | Animation steps or wait steps. |
| `callbacks` | Optional named lifecycle callbacks; see the [Animation format](animation.md#callbacks). |

Sequence JSON files are limited to 8 MiB.

!!! tip inline end "Time units"
    Emote uses Minecraft time format.<br>
    `1s` equals `20t`.

    `s`: seconds<br>
    `t` or omitted: ticks<br>
    `d`: Minecraft days

## Metadata and settings

- `metadata.name`: Name shown in commands and the emote UI.
- `metadata.description`: Description shown to players.
- Additional metadata is preserved and exposed to the API and web converter.
- `settings.cooldown`: Cooldown applied after a successful Sequence ends.
- `settings.player`: Player visibility and stop conditions for the entire Sequence. These replace the referenced Animations' player settings.

Each stop-condition field matches the player-behavior setting in the [Animation format](animation.md).

Sequences do not define `standalone`, `rotation_deadzone`, or `playback`. They are directly playable entries. Each active Animation step supplies its own `rotation_deadzone`; the Sequence controls the cooldown and player behavior.

## Animation steps

Specify one Animation with `emote` and optionally add `repeat` or `transition`.

```json
{"emote": "emote:idle_sky", "repeat": 3}
```

`repeat` defaults to `1`. Each repetition runs one complete playback cycle of the Animation. Repeating Animations include their `loop_delay` between cycles.

`transition` linearly moves the shared display nodes from the previous Animation's final pose to the next Animation's initial pose before its timeline begins:

```json
{"emote": "emote:stand_up1", "transition": "4t"}
```

It defaults to `0t` and applies before every real Animation selected by the step, including repetitions. The first Animation of a Sequence has no previous pose, so its transition is skipped. Waits and loop delays hold the previous pose before the transition. Visibility, display data, timeline events, and Molang time change only when the next Animation begins.

The referenced Animation must be loaded and valid. Animations with `standalone: false` may be used, but other Sequences and Animations using `hold` or `server_sync` playback may not be referenced.

Referenced Animations retain all four event groups. `start` runs when the Animation segment begins, timeline events use the segment-local tick, `loop` runs when a loop segment completes, and `stop` runs when the segment completes or when the Sequence is interrupted while that segment is active. At a shared boundary, the outgoing Animation stops before the incoming Animation starts.

## Random selection

An array of IDs selects one with equal probability on each repetition.

```json
{
  "emote": [
    "emote:idle_sky",
    "emote:idle_butterfly",
    "emote:idle_flower"
  ],
  "repeat": 3
}
```

For explicit probabilities, alternate IDs and integer weights. The weights must total `100`.

```json
{
  "emote": [
    "emote:idle_sky", 40,
    "emote:idle_butterfly", 35,
    "emote:idle_flower", 25
  ],
  "repeat": 3
}
```

A candidate is selected again on every repetition. When multiple Animation candidates exist, the most recently selected Animation is excluded and the remaining probabilities are normalized automatically. Sequence control IDs do not count toward the number of Animation candidates.

## Repeat control

Two reserved IDs control repetition of the current Animation step.

| ID | Behavior |
|---|---|
| `emote:continue` | Consumes the current repetition and selects the next one without adding an Animation or `loop_delay`. |
| `emote:break` | Ends the current repeat loop and advances to the next Sequence step. |

```json
{
  "emote": [
    "emote:idle_sky", 50,
    "emote:idle_butterfly", 30,
    "emote:continue", 15,
    "emote:break", 5
  ],
  "repeat": 10
}
```

`emote:continue` may be selected consecutively. `emote:break` ends only the current Animation step, not the entire Sequence. A Sequence must contain at least one real Animation candidate.

## Wait steps

```json
{"wait": "10t"}
```

A wait step cannot be the first or last step, cannot be adjacent to another wait step, and cannot use `repeat`.

## Animation compatibility

Animations in one Sequence may use different node IDs. The compiled Sequence creates the union of their nodes once, reuses those display entities throughout playback, and hides nodes that are absent from the active Animation step.

All referenced Animations must use the same compiled player-skin layout: node IDs and body regions derived from `part`, `order`, and local Y scale.

When two Animations reuse the same node ID, that node must have compatible display content:

- Node types must match.
- Item stacks, display contexts, block states, text, and entity NBT must match.
- Player skin binding must match.

Local transforms, initial visibility, and timeline tracks may differ. Each Animation step evaluates its own node transforms, visibility, and Molang session. Control IDs are excluded from compatibility checks.

Timeline command events and the `start`, `loop`, and `stop` event groups are preserved as described above.

## Playback behavior

Referenced Animations are resolved and checked for compatibility when emotes are reloaded. Before playback, random choices and repeat controls are resolved and the selected steps are compiled into one playback. Display entities are created once and reused until the Sequence ends.

The Sequence's player settings replace those of referenced Animations. Stopping or interrupting the Sequence cancels all remaining steps.

The Sequence and its referenced Animations have independent callbacks and per-playback state.
