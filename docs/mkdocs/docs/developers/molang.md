# Molang

Animation schema 5 accepts Molang for initialization and ticks, track values, curve parameters, shared values, animation time, and delays. Both the short and long prefixes are supported: `q` or `query`, `v` or `variable`, and `t` or `temp`.

## Track values

Each component of a translation, Euler rotation, or scale track may be a number or an object containing a `molang` string:

```json
{
  "target": {"node": "n1", "operation": "spin"},
  "channel": "value",
  "driver": {
    "type": "expression",
    "value": [0, {"molang": "q.target_y_rotation"}, 0]
  }
}
```

Visibility values may be booleans or objects containing a `molang` string. A finite result of `0` is hidden; any other finite result is visible.

The same `{ "molang": "..." }` form is used for dynamic easing parameters, curve handles and tangents, and `blend.weight`. A blended three-component track combines `blend.base` with the driver's result using that weight.

### Shared values

`animation.values` defines reusable scalar or three-component drivers. Each definition has a numeric `base` array and uses an `expression` or `curve` driver. Read a component with `q.value('id', index)`.

```json
"values": {
  "sway": {
    "type": "expression",
    "base": [0],
    "value": [{"molang": "math.sin(q.anim_time * 180) * 0.1"}]
  }
}
```

A shared curve's optional `time_offset` changes only its sampling time; it does not change animation time, queries, or events.

### Dynamic NBT values

An NBT keyframe can use Molang to produce a compound SNBT string:

```json
"value": {
  "merge": {"molang": "q.is_sneaking ? '{Glowing:1b}' : '{Glowing:0b}'"},
  "remove": []
}
```

The expression follows the same read-only track-expression rules as vector and visibility values. Its result must be a string containing valid compound SNBT. It is evaluated once when the keyframe is applied, rather than on every animation tick; a new playback loop or Sequence segment evaluates it again. `q.key_frame_lerp_time` is `0` during evaluation.

NBT keys apply `merge` and `remove` patches. Runtime-owned fields remain forbidden.

## Initialization and ticks

Use the optional `animation.molang` object when an Animation needs to initialize variables or update persistent state on ticks:

```json
"animation": {
  "molang": {
    "initialize": "v.speed = 2;",
    "tick": "v.phase = v.phase + q.delta_time * v.speed;"
  }
}
```

- `initialize` runs when Animation playback begins.
- `tick` runs before track values are evaluated on each animation tick.
- `v.*` variables persist within the Animation playback and may be assigned by `initialize` and `tick`.
- `t.*` variables exist only for one expression evaluation.
- `q.*` values are read-only. Assigning a query is rejected.

A Molang session is retained across an Animation's loops. Each Animation segment in a Sequence creates its own session.

Track expressions, clock expressions, delays, and curve parameters can read persistent variables but cannot assign them. Use `initialize` and `tick` for those assignments. An Animation that only needs formulas in its tracks can omit `animation.molang`.

## Animation time and delays

Normally, animation time advances with elapsed server ticks. Set `animation.clock.type` to `molang` and provide `expression` to calculate the next timeline position in seconds:

```json
"clock": {
  "type": "molang",
  "expression": "q.anim_time + q.delta_time * v.speed"
}
```

This fragment belongs inside `animation`. A custom clock can change the playback speed or move backward along the timeline. Timeline event `direction` determines which events run when the clock moves forward or backward.

`animation.playback.start_delay` and `loop_delay` accept either Minecraft time strings or a Molang object. Molang delay results use seconds:

```json
"playback": {
  "mode": "loop",
  "start_delay": "5t",
  "loop_delay": {"molang": "q.is_sneaking ? 0.5 : 0"}
}
```

`start_delay` is calculated when the Animation starts, and `loop_delay` is calculated at each completed loop. Results must be finite and non-negative.

`server_sync` playback must be reconstructible from server time. It cannot use a Molang clock, `animation.molang.tick`, Molang delays, or dynamic NBT keys.

## Math functions

All functions in the Bedrock Molang `math.*` reference are supported, including the easing, random, dice, interpolation, angle, and sign functions. Trigonometric inputs and outputs use degrees.

## Conditionals

The examples in this section and [Loops](#loops) assign persistent variables, so use them in `animation.molang.initialize` or `animation.molang.tick`.

Bedrock's binary and ternary conditional operators are supported. Only the selected branch is evaluated, and either branch may contain a brace-delimited execution scope:

```molang
q.is_moving ? {
  v.speed = q.ground_speed;
} : {
  v.speed = 0;
};
return v.speed;
```

Conditional expressions associate from the right, so `a ? b : c ? d : e` is equivalent to `a ? b : (c ? d : e)`. Molang does not define separate `if` or `else` keywords; use `?` and `:` instead.

## Loops

`loop(count, { ... })` repeats an execution scope and supports `break` and `continue`. The runtime limits one loop to 1,024 iterations:

```molang
v.count = 0;
loop(3, {
  v.count = v.count + 1;
});
return v.count;
```

## Queries

The table uses the `q.*` form. The equivalent `query.*` names are also accepted.

| Query | Value |
|---|---|
| `q.anim_time` | Current timeline time in seconds. |
| `q.anim_time_ticks` | Current timeline time in ticks. |
| `q.anim_length` | Timeline duration in seconds. |
| `q.delta_time` | `0.05` during normal ticks and `0` when playback is initialized. |
| `q.loop_count` | Zero-based number of completed loops. |
| `q.key_frame_lerp_time` | Uneased curve-segment progress from `0` to `1`; exact-key sampling follows the driver's `at_key` policy. |
| `q.life_time` | Elapsed Animation playback time in seconds; continues across timeline loops. |
| `q.target_x_rotation`, `q.target_y_rotation` | Player look pitch and head yaw relative to the body, in degrees. |
| `q.body_x_rotation`, `q.body_y_rotation` | Player pitch and absolute body yaw, in degrees. |
| `q.head_x_rotation`, `q.head_y_rotation` | Player pitch and absolute head yaw, in degrees. |
| `q.eye_target_x_rotation`, `q.eye_target_y_rotation` | Player eye pitch and absolute head yaw, in degrees. |
| `q.ground_speed` | Player horizontal movement speed in blocks per second. |
| `q.vertical_speed` | Player vertical movement speed in blocks per second; positive is upward. |
| `q.modified_distance_moved` | Player walk-animation position used by imported Bedrock movement formulas. |
| `q.walk_distance` | Player accumulated movement distance. |
| `q.is_moving` | `1` while the player has non-zero movement, otherwise `0`. |
| `q.is_on_ground` | `1` while the player is on the ground, otherwise `0`. |
| `q.is_sneaking` | `1` while the player is crouching, otherwise `0`. |
| `q.is_sprinting` | `1` while the player is sprinting, otherwise `0`. |
| `q.is_swimming` | `1` while the player is swimming, otherwise `0`. |
| `q.is_gliding` | `1` while the player is gliding with an elytra, otherwise `0`. |
| `q.is_riding` | `1` while the player is riding another entity, otherwise `0`. |
| `q.is_using_item` | `1` while the player is using an item, otherwise `0`. |
| `q.is_sleeping` | `1` while the player is sleeping, otherwise `0`. |
| `q.is_emoting` | `1` during player-backed emote playback, otherwise `0`. |
| `q.item_is_charged` | `1` while the player's main-hand crossbow is charged, otherwise `0`. |
| `q.sleep_rotation` | Yaw of the bed occupied by the player, or `0` while not sleeping. |
| `q.is_on_fire` | `1` while the player is on fire, otherwise `0`. |
| `q.is_in_water` | `1` while the player is in water, otherwise `0`. |
| `q.health`, `q.max_health` | Player current and maximum health in health points (two points per heart). |
| `q.is_alive` | `1` while the player is alive, otherwise `0`. |
| `q.is_spectator` | `1` while the player is in spectator mode, otherwise `0`. |
| `q.head_is_in_water` | `1` while the player's eyes are in water, otherwise `0`. |
| `q.is_in_lava` | `1` while the player is in lava, otherwise `0`. |
| `q.is_in_water_or_rain` | `1` while the player is in water or exposed to rain, otherwise `0`. |
| `q.hurt_time` | Remaining player hurt-animation time in ticks. |
| `q.death_ticks` | Elapsed player death-animation time in ticks. |
| `q.invulnerable_ticks` | Remaining player invulnerability timer in ticks; this is not the permanent invulnerability flag. |
| `q.player_level` | Player experience level. |
| `q.item_in_use_duration` | Elapsed active item-use time in seconds, capped at the item's maximum use duration. |
| `q.item_remaining_use_duration` | Remaining active item-use time in seconds. |
| `q.item_max_use_duration` | Maximum active item-use duration in seconds. |
| `q.is_item_equipped` | Main-hand shortcut: `1` when the main hand is not empty, otherwise `0`. |
| `q.blocking` | `1` while the player is actively blocking, otherwise `0`. |
| `q.is_eating` | `1` while the player is using an item with the eat animation, otherwise `0`. |
| `q.is_jumping` | `1` while the player's latest client input has jump held, otherwise `0`. |
| `q.is_crawling` | `1` while the player has the crawling pose without swimming, otherwise `0`. |
| `q.is_invisible` | `1` while the player is invisible, otherwise `0`. |
| `q.is_levitating` | `1` while the player has the levitation effect, otherwise `0`. |
| `q.yaw_speed` | Player yaw change during the current tick, in degrees. |
| `q.on_fire_time` | Remaining player fire time in seconds. |

The three item-use duration queries refer to the item currently being used, in either hand, and return `0` when no item is being used.

### Query functions

Query functions are available wherever Molang is accepted.

| Query | Value |
|---|---|
| `q.any(value, candidate, ...)` | `1` if any candidate equals the first value. Strings and numbers retain their types. |
| `q.all(value, candidate, ...)` | `1` if every candidate equals the first value. Strings and numbers retain their types. |
| `q.approx_eq(value, candidate, ...)` | `1` if every numeric candidate approximately equals the first value. |
| `q.in_range(value, minimum, maximum)` | `1` if the value is within the inclusive range. |
| `q.position(axis)` | Player position on axis `0` (X), `1` (Y), or `2` (Z). |
| `q.position_delta(axis)` | Player movement during the current tick on the selected axis. |
| `q.movement_direction(axis)` | Selected component of the normalized current movement vector, or `0` while stationary. |
| `q.is_item_equipped()` | `1` if the main hand is not empty. An optional hand or equipment-slot selector may be supplied. |
| `q.item_is_charged()` | `1` if the main-hand crossbow is charged. An optional hand or equipment-slot selector may be supplied. |
| `q.is_item_name_any(slot, name, ...)` | `1` if the selected equipment item has one of the full identifiers, such as `'minecraft:bow'`. |
| `q.scoreboard(objective)` | Player score for the named objective, or `0` when the objective or score is absent. |
| `q.value(id, component)` | Component of a shared driver in `animation.values`; component indices start at `0`. |

Item selectors accept `main_hand`, `off_hand`, `slot.weapon`, `slot.weapon.mainhand`, `slot.weapon.offhand`, and `slot.armor.head`, `slot.armor.chest`, `slot.armor.legs`, or `slot.armor.feet`. Numeric hand selector `0` means main hand and `1` means off hand. Scalar `q.is_item_equipped` and `q.item_is_charged` remain available as main-hand shortcuts.

## Validation

Invalid syntax or unsupported queries prevent an Animation from loading. A numeric value that evaluates to a non-finite number stops playback.

## Current limitations

This is not a complete Bedrock Molang environment. Only the queries and query functions listed above are available. Context values, `this`, structs, arrays, resource values, entity references, the arrow operator's entity switching, and `for_each` are not supported. Null coalescing and string equality may behave differently from Bedrock Molang.
