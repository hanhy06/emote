# Emote sequence format

Sequence files use schema version 3 and combine existing animations into one playable emote.

```json
{
  "type": "sequence",
  "schema_version": 3,
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

See [emote-sequence-format.json](./emote-sequence-format.json) for a complete example with weighted random selection.

## Root fields

| Field | Description |
|-------|-------------|
| `type` | Must be `sequence`. |
| `schema_version` | Must be `3`. |
| `id` | Lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and optional custom metadata. |
| `participants` | Required participant placements for a two-player sequence; omitted for a single-player sequence. |
| `settings` | Cooldown and player behavior for the whole sequence. |
| `steps` | Animation and wait steps, or one `await_partner` step. |

Sequence JSON files are limited to 8 MiB.

## Migrating from schema 1

The web converter can import a released schema 1 sequence and export it as schema 3. The server itself only loads schema 3 files.

| Schema 1 | Schema 3 |
|----------|----------|
| `schema_version: 1` | `schema_version: 3` |
| Root `player` | `settings.player` |
| No cooldown | `settings.cooldown`; migration uses `"0t"`. |
| Schema 1 animation references | Convert each animation using “Migrating from schema 1” in the [animation format](./emote-animation-format.md). |

Existing animation steps, repeats, and equal or weighted random choices keep the same structure. Schema 3 additionally supports wait steps using Minecraft time strings. The sequence's player settings now apply to the whole sequence and replace the referenced animations' player settings.

Two-player sequences are entirely new in schema 3. They add `participants`, participant-relative animation node spaces, and the `await_partner`, `matched`, and `timeout` branches described below.

## Time values

Gameplay time values are strings using Minecraft time units:

| Example | Meaning |
|---------|---------|
| `"1d"` | One Minecraft day. |
| `"5s"` | Five seconds. |
| `"20"` / `"20t"` | Twenty ticks. The `t` suffix is optional. |

This format is used by `cooldown`, `wait`, and `await_partner.timeout`.

## Metadata

- `name` is the name shown in commands and the emote UI.
- `description` is the description shown to players.
- Additional fields are preserved and exposed through the API and web converter.

## Settings

### Cooldown

- `cooldown` is applied to the player after the sequence starts successfully.

### Player behavior

- `hidden` controls whether the original player is visible during the sequence.
- `stop_conditions` controls interruptions for the entire sequence using the fields under “Player behavior” in the [animation format](./emote-animation-format.md).

## Animation steps

An animation step uses `emote` to select one animation and optionally uses `repeat`:

```json
{"emote": "example:sit_idle", "repeat": 3}
```

`repeat` defaults to `1`. Each repeat plays one complete animation cycle. Looping animations include their `loop_delay` between repeated cycles.

The referenced animation must be loaded and enabled. A sequence can use animations with `standalone: false`, but it cannot reference another sequence or an animation with `server_sync` playback.

## Random selection

Use an array of IDs to select each repeat with equal probability:

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

To assign explicit chances, alternate each ID with an integer weight. The weights must total `100`:

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

A candidate is selected again for every repeat. When multiple animation candidates are available, the previously selected animation is excluded and the remaining chances are normalized automatically. Sequence controls are not counted as animation alternatives.

## Repeat controls

Two reserved IDs control the current animation step's `repeat` loop:

| ID | Behavior |
|---|---|
| `emote:continue` | Consumes the current repeat without adding an animation or `loop_delay`, then selects the next repeat. |
| `emote:break` | Stops the current repeat loop and continues with the next sequence step. |

Controls can be used anywhere an animation candidate is accepted, including equal and weighted random arrays:

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

`emote:continue` may be selected consecutively. `emote:break` exits only the current animation step; it does not end the entire sequence or collaboration branch. With the default `repeat` of `1`, both controls skip the current animation step. A sequence must contain at least one real animation candidate, and collaboration offer animations cannot use a control ID.

## Wait steps

A wait step adds a delay between animation steps:

```json
{"wait": "10t"}
```

Wait steps cannot be first, last, consecutive, or combined with `repeat`.

## Animation compatibility

Every animation candidate used by a sequence must have the same node IDs and compatible node content. Control candidates are ignored by compatibility checks:

- node types must match;
- item stacks, item display contexts, block states, text, and display entity NBT must match; and
- player skin parts and their order must match.

Default transforms and visibility may differ between animations. They are reset when each animation step begins.

Timeline command events are preserved. Start, loop, and stop command events are not supported in animations referenced by a sequence.

## Playback behavior

Referenced animations are resolved and checked for compatibility when emotes are reloaded. Before playback, the selected steps are compiled into one animation. Its display entities are created once and reused until the sequence finishes.

The sequence's `player` settings replace the referenced animations' player settings. Stopping or interrupting the sequence cancels all remaining steps.

## Two-player sequences

A two-player sequence adds top-level `participants` and contains one `await_partner` step. See [emote-two-player-sequence-format.json](./emote-two-player-sequence-format.json) for a complete example.

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
      "timeout": [{"emote": "emote:handshake_close"}]
    }
  ]
}
```

`participants` must define both `initiator` and `partner`. Participant positions use Minecraft relative coordinates: all three components must use `~` or `^`, and absolute components are rejected. `~` is relative to the scene origin and `^` is relative to the initiator's horizontal view direction. Participant rotations use Minecraft rotation syntax. These placements determine the roots used by `initiator` and `partner` animation nodes after a match.

The sequence must contain exactly one top-level step, and that step must be `await_partner`. Its fields are:

| Field | Description |
|-------|-------------|
| `await_partner.emote` | Offer animation played by the initiator while waiting. |
| `await_partner.timeout` | Positive time before the offer takes the timeout branch. |
| `matched` | Non-empty animation/wait branch played after a partner joins. |
| `timeout` | Non-empty animation/wait branch played if nobody joins. |

`repeat` is not supported on the `await_partner` step. The `matched` and `timeout` arrays follow the normal animation-step and wait-step rules, but cannot contain another `await_partner` step.

### Partner matching

The offer animation plays for the initiator while the partner space remains hidden. Another player joins by starting the same sequence while all of these conditions hold:

- both players are alive and in the same dimension;
- they are no more than 2 blocks apart horizontally and 1 block apart vertically;
- each player faces the other within 45 degrees; and
- the initiator has line of sight to the partner.

If more than one compatible offer is nearby, the nearest initiator is selected. A partner is reserved when they join and must still satisfy the matching conditions when the offer finishes. The matched branch begins at the end of the offer, or immediately when the offer is already holding. If the reservation becomes invalid, the initiator continues waiting until another partner joins or the timeout expires.

### Symmetric and asymmetric animations

If compatible animations contain no `partner` nodes, every `initiator` node, skin binding, transform track, and visibility track is duplicated automatically for the partner. The duplicate uses the partner root, so a partner rotation such as `~180 0` turns the same local animation to face the initiator. If any `partner` node exists, the sequence is treated as explicitly asymmetric and no automatic partner nodes are generated.
