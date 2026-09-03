# Molang

Animation schema 4 accepts Molang in animation programs, vector track components, visibility track values, and NBT option selectors. Both the short and long prefixes are supported: `q` or `query`, `v` or `variable`, and `t` or `temp`.

## Programs

Animation-level programs are optional:

```json
"molang": {
  "initialize": "v.speed = 2;",
  "tick": "v.phase = v.phase + q.delta_time * v.speed;"
}
```

- `initialize` runs when a playback cycle begins.
- `tick` runs before track values are evaluated on each animation tick.
- `v.*` variables persist within the current cycle and may be assigned by `initialize` and `tick`.
- `t.*` variables exist only for one expression evaluation.
- `q.*` values are read-only. Assigning a query is rejected.
- `server_sync` Animations cannot use `molang.tick` because their timeline may begin at an arbitrary synchronized time.

A new Molang session is created for every repeated cycle and every Animation segment in a Sequence.

## Track values

Each position, rotation, or scale component may be a number or a Molang string:

```json
"rotation": [
  {
    "time": "0t",
    "value": [0, "q.target_y_rotation", "v.offset"]
  }
]
```

Visibility values may be booleans or Molang strings. A finite result of `0` is hidden; any other finite result is visible.

Track values may read persistent variables but cannot assign them. Temporary variables are cleared for each expression evaluation.

### NBT option selectors

An NBT keyframe can use Molang to choose one of two or more compound SNBT options:

```json
"value": {
  "select": "math.random_integer(0, 3)",
  "options": [
    "{item:{id:'minecraft:poppy',count:1}}",
    "{item:{id:'minecraft:dandelion',count:1}}",
    "{item:{id:'minecraft:blue_orchid',count:1}}"
  ]
}
```

The selector follows the same read-only track-expression rules as vector and visibility values. Its result must be a finite integer within the option array. It is evaluated once when the keyframe is applied, rather than on every animation tick; a new playback loop or Sequence segment evaluates it again. `q.key_frame_lerp_time` is `0` during selector evaluation.

## Queries

The table uses the `q.*` form. The equivalent `query.*` names are also accepted.

| Query | Value |
|---|---|
| `q.anim_time` | Current timeline time in seconds. |
| `q.anim_time_ticks` | Current timeline time in ticks. |
| `q.anim_length` | Timeline duration in seconds. |
| `q.delta_time` | `0.05` during normal ticks and `0` when a cycle is initialized. |
| `q.loop_count` | Zero-based number of completed loops. |
| `q.key_frame_lerp_time` | Uneased progress from the current vector keyframe to the next, from `0` to `1`; `0` outside vector evaluation. |
| `q.life_time` | Alias of `q.anim_time`, for imported Molang compatibility. |
| `q.target_x_rotation`, `q.target_y_rotation` | Initiator look pitch and head yaw relative to the body, in degrees. |
| `q.body_x_rotation`, `q.body_y_rotation` | Initiator pitch and absolute body yaw, in degrees. |
| `q.head_x_rotation`, `q.head_y_rotation` | Initiator pitch and absolute head yaw, in degrees. |
| `q.eye_target_x_rotation`, `q.eye_target_y_rotation` | Initiator eye pitch and absolute head yaw, in degrees. |
| `q.ground_speed` | Initiator horizontal movement speed in blocks per second. |
| `q.vertical_speed` | Initiator vertical movement speed in blocks per second; positive is upward. |
| `q.modified_distance_moved` | Initiator walk-animation position used by imported Bedrock movement formulas. |
| `q.walk_distance` | Initiator accumulated movement distance. |
| `q.is_moving` | `1` while the initiator has non-zero movement, otherwise `0`. |
| `q.is_on_ground` | `1` while the initiator is on the ground, otherwise `0`. |
| `q.is_sneaking` | `1` while the initiator is crouching, otherwise `0`. |
| `q.is_sprinting` | `1` while the initiator is sprinting, otherwise `0`. |
| `q.is_swimming` | `1` while the initiator is swimming, otherwise `0`. |
| `q.is_gliding` | `1` while the initiator is gliding with an elytra, otherwise `0`. |
| `q.is_riding` | `1` while the initiator is riding another entity, otherwise `0`. |
| `q.is_using_item` | `1` while the initiator is using an item, otherwise `0`. |
| `q.is_sleeping` | `1` while the initiator is sleeping, otherwise `0`. |
| `q.is_emoting` | `1` during player-backed emote playback, otherwise `0`. |
| `q.item_is_charged` | `1` while the initiator's main-hand crossbow is charged, otherwise `0`. |
| `q.sleep_rotation` | Yaw of the bed occupied by the initiator, or `0` while not sleeping. |
| `q.is_on_fire` | `1` while the initiator is on fire, otherwise `0`. |
| `q.is_in_water` | `1` while the initiator is in water, otherwise `0`. |
| `q.health`, `q.max_health` | Initiator current and maximum health in health points (two points per heart). |
| `q.is_alive` | `1` while the initiator is alive, otherwise `0`. |
| `q.is_spectator` | `1` while the initiator is in spectator mode, otherwise `0`. |
| `q.head_is_in_water` | `1` while the initiator's eyes are in water, otherwise `0`. |
| `q.is_in_lava` | `1` while the initiator is in lava, otherwise `0`. |
| `q.is_in_water_or_rain` | `1` while the initiator is in water or exposed to rain, otherwise `0`. |
| `q.hurt_time` | Remaining initiator hurt-animation time in ticks. |
| `q.death_ticks` | Elapsed initiator death-animation time in ticks. |
| `q.invulnerable_ticks` | Remaining initiator invulnerability timer in ticks; this is not the permanent invulnerability flag. |
| `q.player_level` | Initiator experience level. |
| `q.item_in_use_duration` | Elapsed active item-use time in seconds, capped at the item's maximum use duration. |
| `q.item_remaining_use_duration` | Remaining active item-use time in seconds. |
| `q.item_max_use_duration` | Maximum active item-use duration in seconds. |

Player-state queries always refer to the initiator, including partner Animations. Synthetic stress-test playback has no initiator and evaluates these queries as `0`.

The three item-use duration queries refer to the item currently being used, in either hand, and return `0` when no item is being used. They are scalar queries without arguments; hand-slot selection and normalization arguments are not supported.

## Validation and preview

Each Molang source string is limited to 16,384 characters. Invalid syntax, unsupported query names, query assignments, and persistent-variable assignments in track values reject the Animation during loading. A value that evaluates to a non-finite number stops playback as a runtime failure; an NBT selector also fails if its result is fractional or outside its option array.

The web converter preserves the original schema 4 Molang source when exporting. Its preview evaluates deterministic expressions with synthetic player state: `q.is_on_ground` and `q.is_emoting` are `1`, while the other player-state queries are `0`. For a nondeterministic NBT selector, preview displays the first option while export preserves the selector and every option. If another expression cannot be evaluated safely, export remains available and the preview falls back to the Create pose.
