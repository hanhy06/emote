# Emote animation schema 2

Animation files are JSON objects with five required root sections:

```json
{
  "type": "animation",
  "schema_version": 2,
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

## Time values

Every gameplay time value is a string parsed with Minecraft's time argument rules. Supported suffixes are `d` for Minecraft days, `s` for seconds, and `t` for ticks. A bare integer also means ticks. Examples: `"1d"`, `"5s"`, `"20t"`, and `"20"`.

This applies to `settings.cooldown`, `settings.playback.loop_delay`, `timeline.duration`, keyframe and event `time`, and `interpolation_duration`. `loop_delay` must be zero when playback mode is `once`.

## Metadata and settings

- `id` is a lowercase Minecraft `namespace:path` identifier.
- `metadata.name` and `metadata.description` are required. Any additional metadata fields are preserved and exposed through the API and web editor.
- `settings.standalone` controls whether the animation can be selected and played directly. A non-standalone animation can still be used by a sequence.
- `settings.cooldown` is applied per player and emote after successful playback.
- `settings.player.hidden` hides the original player while the emote runs.
- `settings.player.stop_conditions` defines interruptions. `movement_distance` is a non-negative horizontal block distance; zero disables movement interruption.
- `settings.playback.mode` is `once`, `loop`, or `server_sync`.

## Nodes

Each property of `nodes` is a stable node ID. Every node has a 16-number `default_matrix` in column-major transformation form.

- `item_display` requires `item_stack_snbt` and `item_display`. It may include player `skin` mapping with a body `part` and non-negative `order`.
- `block_display` requires `block_state_snbt`.
- `text_display` requires a text component in `text`.
- `anchor` has no display entity and is used as a command origin or source.
- Display nodes may include `visible` and `entity_nbt`.

## Timeline and commands

`timeline.keyframes` changes node transforms and visibility. A keyframe-level `interpolation_duration` applies to its transforms unless an individual node transform overrides it.

`timeline.events` may contain `start`, `timeline`, `loop`, and `stop` command arrays. Timeline events additionally require `time`. A command `source` is `player`, `server`, or a named `node`; its `origin` is the animation `root` or a named `node`, with an optional three-number `offset`.

See [emote-animation-format.json](./emote-animation-format.json) for a complete example containing every node and event form.
