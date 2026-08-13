# Emote sequence format

Sequence files use schema version 2 and combine existing animations into one playable emote.

```json
{
  "type": "sequence",
  "schema_version": 2,
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
| `schema_version` | Must be `2`. |
| `id` | Lowercase Minecraft identifier in `namespace:path` form. |
| `metadata` | Display name, description, and optional custom metadata. |
| `settings` | Cooldown and player behavior for the whole sequence. |
| `steps` | Animation and wait steps in playback order. |

Sequence JSON files are limited to 8 MiB.

## Metadata and settings

`metadata` uses the same `name`, `description`, and optional custom fields as an [animation](./emote-animation-format.md#metadata).

- `cooldown` is applied to the player after the sequence starts successfully.
- `player.hidden` controls whether the original player is visible during the sequence.
- `player.stop_conditions` controls interruptions for the entire sequence.

Time values use Minecraft time units: `d` for Minecraft days, `s` for seconds, and `t` for ticks. The `t` suffix is optional, so `"20"` and `"20t"` both mean twenty ticks.

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

A candidate is selected again for every repeat. When alternatives are available, the previous candidate is excluded and the remaining chances are normalized automatically.

## Wait steps

A wait step adds a delay between animation steps:

```json
{"wait": "10t"}
```

Wait steps cannot be first, last, consecutive, or combined with `repeat`.

## Animation compatibility

Every candidate used by a sequence must have the same node IDs and compatible node content:

- node types must match;
- item stacks, item display contexts, block states, text, and display entity NBT must match; and
- player skin parts and their order must match.

Default transforms and visibility may differ between animations. They are reset when each animation step begins.

Timeline command events are preserved. Start, loop, and stop command events are not supported in animations referenced by a sequence.

## Playback behavior

Referenced animations are resolved and checked for compatibility when emotes are reloaded. Before playback, the selected steps are compiled into one animation. Its display entities are created once and reused until the sequence finishes.

The sequence's `player` settings replace the referenced animations' player settings. Stopping or interrupting the sequence cancels all remaining steps.
