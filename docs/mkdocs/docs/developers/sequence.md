# Sequence

Sequence files use schema version `4` and combine existing Animations into one emote.

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "example:sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down, wait, and stand up."
  },
  "settings": {
    "cooldown": "5s",
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
    {"emote": "example:sit_down"},
    {"wait": "10t"},
    {"emote": "example:sit_idle", "repeat": 3},
    {"emote": "example:stand_up"}
  ]
}
```

[Sequence reference JSON](https://github.com/hanhy06/emote/blob/dev/docs/reference/sequence.json) and a [two-player reference](https://github.com/hanhy06/emote/blob/dev/docs/reference/two-player-sequence.json) are provided separately.

## Root fields

| Field | Description |
|---|---|
| `type` | Must be `sequence`. |
| `schema_version` | Must be `4`. |
| `id` | A lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and custom metadata. |
| `participants` | Participant placement required by two-player Sequences; omitted for single-player Sequences. |
| `settings` | Cooldown and player behavior for the entire Sequence. |
| `steps` | Animation steps, wait steps, or one `await_partner` step. |

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
- `settings.cooldown`: Cooldown applied after the Sequence starts successfully.
- `settings.player`: Player visibility and stop conditions for the entire Sequence. These replace the referenced Animations' player settings.

Each stop-condition field matches the player-behavior setting in the [Animation format](animation.md).

## Animation steps

Specify one Animation with `emote` and optionally add `repeat`.

```json
{"emote": "example:sit_idle", "repeat": 3}
```

`repeat` defaults to `1`. Each repetition runs one complete playback cycle of the Animation. Repeating Animations include their `loop_delay` between cycles.

The referenced Animation must be loaded and valid. Animations with `standalone: false` may be used, but other Sequences and `server_sync` Animations may not be referenced.

## Random selection

An array of IDs selects one with equal probability on each repetition.

```json
{
  "emote": [
    "example:sit_idle_1",
    "example:sit_idle_2",
    "example:sit_idle_3"
  ],
  "repeat": 3
}
```

For explicit probabilities, alternate IDs and integer weights. The weights must total `100`.

```json
{
  "emote": [
    "example:sit_idle_1", 30,
    "example:sit_idle_2", 40,
    "example:sit_idle_3", 30
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
    "example:sit_idle_1", 50,
    "example:sit_idle_2", 30,
    "emote:continue", 15,
    "emote:break", 5
  ],
  "repeat": 10
}
```

`emote:continue` may be selected consecutively. `emote:break` ends only the current Animation step, not the entire Sequence or cooperative branch. A Sequence must contain at least one real Animation candidate, and control IDs cannot be used in cooperative offer Animations.

## Wait steps

```json
{"wait": "10t"}
```

A wait step cannot be the first or last step, cannot be adjacent to another wait step, and cannot use `repeat`.

## Animation compatibility

Animations in one Sequence may use different node IDs. The compiled Sequence creates the union of their nodes once, reuses those display entities throughout playback, and hides nodes that are absent from the active Animation step.

When two Animations reuse the same node ID, that node must have the same inherited space and compatible display content:

- Node types must match.
- Item stacks, display contexts, block states, text, and entity NBT must match.
- Player skin participant, part, and order must match.

Local transforms, initial visibility, and timeline tracks may differ. Each Animation step evaluates its own node transforms, visibility, and Molang session. Control IDs are excluded from compatibility checks.

Timeline command events are preserved. Animations referenced by a Sequence cannot use `start`, `loop`, or `stop` command events.

## Playback behavior

Referenced Animations are resolved and checked for compatibility when emotes are reloaded. Before playback, random choices and repeat controls are resolved and the selected steps are compiled into one playback. Display entities are created once and reused until the Sequence ends.

The Sequence's player settings replace those of referenced Animations. Stopping or interrupting the Sequence cancels all remaining steps.

## Two-player Sequences

A two-player Sequence adds `participants` at the root and contains one `await_partner` step.

```json
{
  "participants": {
    "initiator": {
      "position": "~ ~ ~",
      "rotation": "~ 0"
    },
    "partner": {
      "position": "^ ^ ^1.2",
      "rotation": "~180 0"
    }
  },
  "steps": [
    {
      "await_partner": {
        "emote": "emote:handshake_offer",
        "timeout": "10s"
      },
      "matched": [
        {"emote": "emote:handshake", "repeat": 2},
        {"wait": "1s"},
        {"emote": "emote:handshake_close"}
      ],
      "timeout": [
        {"emote": "emote:handshake_close"}
      ]
    }
  ]
}
```

`participants` must define both `initiator` and `partner`. Positions use Minecraft relative coordinates. All three components must use either `~` or `^`; absolute coordinates are not allowed. `~` is relative to the scene origin, while `^` is relative to the initiating player's horizontal facing direction. Rotations use Minecraft rotation syntax.

The Sequence must contain exactly one top-level step, and it must be `await_partner`.

| Field | Description |
|---|---|
| `await_partner.emote` | Offer Animation played by the initiator while waiting |
| `await_partner.timeout` | Positive time before entering the timeout branch |
| `matched` | Nonempty branch played after a partner joins |
| `timeout` | Nonempty branch played if no partner joins |

The `await_partner` step cannot use `repeat`. `matched` and `timeout` follow the normal Animation and wait-step rules but cannot contain another `await_partner`.

### Partner matching conditions

The offer Animation plays for the initiator, and partner-space content remains hidden until a match. Another player joins by starting the same Sequence while all of these conditions are true:

- Both players are alive and in the same dimension
- Horizontal distance is at most 2 blocks and vertical distance at most 1 block
- Each player faces the other within 45 degrees
- The partner is visible to the initiator

If several compatible offers exist, the nearest initiator is selected. Joining reserves the partner, and the matching conditions are checked again when the offer Animation ends. If the reservation becomes invalid, the initiator keeps waiting until another partner joins or the timeout expires.

### Symmetric and asymmetric Animations

If a compatible Animation has no `partner` nodes, every `initiator` node, skin binding, transformation track, and visibility track is duplicated automatically for the partner. The copies use the partner root, allowing a rotation such as `~180 0` to make the same local Animation face the initiator.

If any `partner` node exists, the Animation is treated as explicitly asymmetric and partner nodes are not generated automatically.

## Migrating from schema 1

The web converter can import published schema 1 Sequences and export them as schema 4. The server only loads schema 4 directly.

| Schema 1 | Schema 4 |
|---|---|
| `schema_version: 1` | `schema_version: 4` |
| Root `player` | `settings.player` |
| No cooldown | `settings.cooldown`; use `"0t"` when migrating |
| References to schema 1 Animations | Convert each Animation to schema 4 |

Schema 4 keeps the existing Sequence structure and behavior unchanged while aligning its version with the Animation format.
