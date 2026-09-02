# Advanced Usage

## Cooldowns

Cooldowns are configured with `settings.cooldown` in each Emote file, not in `emotes.json`.

```json
{
  "settings": {
    "cooldown": "5s"
  }
}
```

!!! tip inline end "Time units"
    Emote uses Minecraft time format.<br>
    `1s` equals `20t`.

    `s`: seconds<br>
    `t` or omitted: ticks<br>
    `d`: Minecraft days

When playing a Sequence, only the Sequence's own `settings.cooldown` applies. Cooldowns of referenced Animations are not added.

---

## Idle Emotes

Idle emotes play automatically after a specified time since the player's last action. Add `idle` to a permission entry in `emotes.json`.

```json
{
  "permission": "emote.default",
  "emotes": ["example:wave", "example:hello"],
  "idle": {
    "delay": "300s",
    "emote": ["example:drink"]
  }
}
```

To choose evenly among several emotes, list only their IDs:

```json
"emote": ["example:drink", "example:look-around"]
```

For weighted selection, alternate IDs and integer weights whose total must equal `100`:

```json
"emote": ["example:drink", 70, "example:look-around", 30]
```

If a player has multiple permissions, entries are checked from top to bottom in `emotes.json`, and the first allowed entry with `idle` is used. Place higher-priority groups first.

```json
"permissions": [
  {
    "permission": "emote.vip",
    "emotes": ["example:vip"],
    "idle": {
      "delay": "120s",
      "emote": ["example:vip-idle"]
    }
  },
  {
    "permission": "emote.default",
    "emotes": ["example:wave"],
    "idle": {
      "delay": "300s",
      "emote": ["example:drink"]
    }
  }
]
```

An idle emote does not start while another emote is playing. A failed attempt is retried after one second. When several candidates are available, Emote avoids selecting the most recently played emote twice in a row when possible.

---

## Sequences

A Sequence connects multiple Animations in order and presents them to the player as one emote. Server operators must install both the Sequence JSON and every Animation JSON it references.

### Example file layout

```text
config/emote/emote/sit/
├── emote.1.sit_down.json
├── emote.2.idle_sky.json
├── emote.3.idle_butterfly.json
├── emote.4.idle_flower.json
├── emote.5.stand_up1.json
├── emote.6.stand_up2.json
└── emote.sit.json
```

`emote.sit.json`:

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "emote:sit",
  "metadata": {
    "name": "Emote sit",
    "description": "Idle emote sit down emote."
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
    {"emote": "emote:sit_down"},
    {
      "emote": [
        "emote:idle_sky", 40,
        "emote:idle_butterfly", 35,
        "emote:idle_flower", 25
      ],
      "repeat": 2
    },
    {"emote": ["emote:stand_up1", 60, "emote:stand_up2", 40]}
  ]
}
```

Intermediate Animations referenced by a Sequence are usually hidden from direct selection:

```json
"settings": {
  "standalone": false,
}
```

---

### Verifying the installation

1. Place all JSON files under `emote/` on the same server.
2. After reloading the files, use `/emote list` to confirm that the Sequence and every referenced Animation loaded.
3. Run `/emote play emote:sit` with normal player permissions.

If the Sequence does not load, check the server log for missing Animation IDs, incompatible nodes, unsupported playback modes, or invalid wait-step messages.

!!! note "Complete example pack"
    The repository includes the ready-to-install [sit sample](https://github.com/hanhy06/emote/tree/dev/docs/sample/sit) shown above and a [two-player handshake sample](https://github.com/hanhy06/emote/tree/dev/docs/sample/handshake).

To create random selection, waits, repeat control, or two-player cooperative Sequences, see the [Sequence format specification](../developers/sequence.md).
