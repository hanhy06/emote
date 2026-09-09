# Advanced Usage

## Cooldowns

Base cooldowns are configured with `settings.cooldown` in each Emote file.

```json
{
  "settings": {
    "cooldown": "5s"
  }
}
```

When playing a Sequence, only the Sequence's own `settings.cooldown` applies. Cooldowns of referenced Animations are not added.

Permission groups can adjust that base cooldown with `cooldown` in `emotes.json`:

```json
{
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": ["*"],
      "cooldown": "x0.5"
    },
    {
      "permission": "emote.default",
      "emotes": ["*"],
      "cooldown": "5s"
    }
  ]
}
```

A Minecraft time value such as `5s` is subtracted from the Emote's base cooldown. A value beginning with `x` multiplies it instead: `x0.5` halves the cooldown, while `x2` doubles it. The final cooldown cannot be less than zero.

Permission entries are checked from top to bottom. The first entry that matches the Emote, belongs to the player, and defines `cooldown` is used. Entries without `cooldown` are skipped, and the base cooldown is unchanged when no matching entry defines one.

!!! tip "Time units"
    Emote uses Minecraft time format.`1s` equals `20t`.

    `s`: seconds<br>
    `t` or omitted: ticks<br>
    `d`: Minecraft days

---

## Idle Emotes

Idle emotes play automatically after a specified time since the player's last action. Add `idle` to a permission entry in `emotes.json`.

```json
{
  "permission": "emote.default",
  "emotes": ["emote:hello", "emote:backflip"],
  "idle": {
    "delay": "300s",
    "emote": ["emote:idle.sit"]
  }
}
```

To choose evenly among several emotes, list only their IDs:

```json
"emote": ["emote:idle.sit", "emote:idle.handstand"]
```

An entry that is a valid emote ID matches literally. Any other entry is a full Java regular expression, so an idle rule can select every currently available emote with a matching ID:

```json
"emote": ["emote:idle\\..*"]
```

Regular-expression entries can be used only with equal selection. They cannot be combined with explicit weights.

For weighted selection, alternate IDs and integer weights whose total must equal `100`:

```json
"emote": ["emote:idle.sit", 70, "emote:idle.handstand", 30]
```

If a player has multiple permissions, entries are checked from top to bottom in `emotes.json`, and the first allowed entry with `idle` is used. Place higher-priority groups first.

```json
"permissions": [
  {
    "permission": "emote.vip",
    "emotes": ["emote:(dance|cheer|clap)"],
    "idle": {
      "delay": "120s",
      "emote": ["emote:idle.handstand"]
    }
  },
  {
    "permission": "emote.default",
    "emotes": ["emote:hello", "emote:backflip"],
    "idle": {
      "delay": "300s",
      "emote": ["emote:idle.sit"]
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
├── emote.idle_butterfly.json
├── emote.idle_flower.json
├── emote.idle_sky.json
├── emote.sit_down.json
├── emote.stand_up1.json
├── emote.stand_up2.json
└── emote.sit.json
```

`emote.sit.json`:

```json
{
  "type": "sequence",
  "schema_version": 4,
  "id": "emote:idle.sit",
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
    {"emote": "emote:sit_down"},
    {
      "emote": ["emote:idle_sky", 40, "emote:idle_butterfly", 35, "emote:idle_flower", 25],
      "transition": "2t",
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
3. Run `/emote play emote:idle.sit` with normal player permissions.

If the Sequence does not load, check the server log for missing Animation IDs, incompatible nodes, unsupported playback modes, or invalid wait-step messages.

!!! note "Complete example pack"
    The repository includes a ready-to-install [two-player handshake sample](https://github.com/hanhy06/emote/tree/dev/docs/sample/handshake). The JSON on this page only demonstrates a linear Sequence and requires separate referenced Animation files.

To create random selection, waits, repeat control, or two-player cooperative Sequences, see the [Sequence format specification](../developers/sequence.md).
