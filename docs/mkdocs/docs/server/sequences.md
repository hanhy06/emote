# Using Sequences

A Sequence connects multiple Animations in order and presents them to the player as one emote. Server operators must install both the Sequence JSON and every Animation JSON it references.

## Example file layout

```text
config/emote/animations/sit/
├── sit-down.json
├── sit-idle.json
├── stand-up.json
└── sit.json
```

`sit.json`:

```json
{
  "type": "sequence",
  "schema_version": 3,
  "id": "example:sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down and stand up after waiting."
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
    {"emote": "example:sit-down"},
    {"emote": "example:sit-idle", "repeat": 3},
    {"emote": "example:stand-up"}
  ]
}
```

Intermediate Animations referenced by a Sequence are usually hidden from direct selection:

```json
"settings": {
  "standalone": false,
  "cooldown": "0t"
}
```

Assign player permissions and cooldowns to the Sequence ID, `example:sit`. You do not need to grant the internal Animation IDs in the player's `emotes` list.

```json
{
  "permission": "emote.default",
  "emotes": ["example:sit"]
}
```

## Verifying the installation

1. Place all JSON files under `animations/` on the same server.
2. After reloading the files, use `/emote list` to confirm that the Sequence and every referenced Animation loaded.
3. Run `/emote play example:sit` with normal player permissions.

If the Sequence does not load, check the server log for missing Animation IDs, incompatible nodes, unsupported playback modes, or invalid wait-step messages.

!!! note "Complete example pack"
    A ready-to-install Sequence example pack will be added to the existing `docs/example/` collection when it is ready. The JSON on this page only demonstrates the structure and requires separate referenced Animation files.

To create random selection, waits, repeat control, or two-player cooperative Sequences, see the [Sequence format specification](../developers/sequence.md).
