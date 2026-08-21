# Access Control

Emote reads access rules from `config/emote/emotes.json`. Permissions are resolved through a Fabric Permissions API-compatible mod such as LuckPerms.

## Permission types

| Permission | Purpose | Default |
|---|---|---|
| `emote.manage` | Use list, reload, enable, disable, stop-other, stop-all, and stress-test commands | Game-master permission level |
| `emote.bypass` | Ignore selection and playback policy for administration and testing | Denied |
| `emote.default` | Built-in group used by `emotes.json` | Allowed unless explicitly denied |
| Custom permission | Server-defined groups such as VIP, supporter, or administrator | Denied unless granted |

`emote.manage` does not grant emote access. Grant `emote.bypass` or add a permission entry containing `"emotes": ["*"]` when an administrator also needs every emote.

Example LuckPerms commands:

```text
/lp group admin permission set emote.manage true
/lp group vip permission set emote.vip true
/lp user <player> permission set emote.bypass true
```

See the [LuckPerms Wiki](https://luckperms.net/wiki/Home) for permission-mod usage.

## Playback policy

| Playback source | Permission | Standalone | Disable | Cooldown |
|---|:---:|:---:|:---:|:---:|
| Wheel and commands | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> |
| Idle | <span style="color: #ef5350">No</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> |
| API | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> |
| emote.bypass | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> | <span style="color: #ef5350">No</span> |

Cooldown does not hide an emote from menus, search results, suggestions, or the wheel. It is checked only when playback is requested.

API and bypass playback still require a loaded ID and remain subject to playback-request listeners, display-entity limits, skin preparation, and other playback-engine failures.

## `emotes.json`

```json
{
  "schema_version": 2,
  "disabled": ["example:broken"],
  "permissions": [
    {
      "permission": "emote.default",
      "emotes": ["example:wave", "example:hello"]
    },
    {
      "permission": "emote.vip",
      "emotes": ["example:dance", "example:sit"]
    },
    {
      "permission": "emote.admin",
      "emotes": ["*"]
    }
  ]
}
```

| Field | Behavior |
|---|---|
| `schema_version` | Must be `2`. |
| `disabled` | IDs normal players and idle playback cannot select or start. Definitions remain loaded. |
| `permissions` | Permission groups used to assemble each player's available emote IDs. |
| `permission` | Permission checked through the installed permission provider. |
| `emotes` | Exact allowed IDs. `"*"` grants every enabled standalone emote. |
| `idle` | Optional idle-playback rule. See [Cooldowns and Idle Emotes](playback-policy.md). |

Normal player access is resolved as follows:

| Priority | Condition | Result |
|---:|---|---|
| 1 | Player has `emote.bypass` | Allow without policy checks. |
| 2 | Animation has `standalone: false` | Deny direct selection and playback. |
| 3 | ID appears in `disabled` | Deny direct selection and playback. |
| 4 | Any granted permission entry contains the ID or `"*"` | Allow. |
| 5 | No permission entry matches | Deny. |

Permission entries are combined for normal emote access, so their order does not change which IDs a player receives. Entry order matters for idle settings because the first granted entry containing `idle` is selected.

!!! note "`emote.default` can be denied"
    `emote.default` is allowed only as its permission-provider default. An explicit `emote.default=false` overrides that default.

## Disabling emotes

| Operation | Effect |
|---|---|
| Add an ID to `disabled` | Hides it from normal selection and rejects normal or idle playback. |
| `/emote disable <id>` | Adds an enabled file-emote ID, stops playback, reloads Emote, and synchronizes client wheels. |
| `/emote enable <id>` | Removes a disabled ID, reloads Emote, and synchronizes client wheels. |
| Reference a disabled Animation from a Sequence | Allowed; only the Sequence ID is checked when the player starts the Sequence. |
| Start a disabled ID through `EmoteApi.play` or with `emote.bypass` | Allowed if the ID is loaded and playback succeeds. |

`/emote disable` accepts file emotes loaded from `animations/`, including Sequences. It does not disable API registrations. Both enable and disable perform a full Emote reload, so all active playback is stopped.

## Bypass limits

| Check | Bypassed by `emote.bypass` |
|---|:---:|
| `standalone` | Yes |
| `disabled` | Yes |
| Emote permission entries | Yes |
| Cooldown | Yes |
| Loaded and valid ID | No |
| Playback-request listener cancellation | No |
| Display-entity limit and playback-start failures | No |
| `emote.manage` requirement | No |
