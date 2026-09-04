# Access Control

Emote reads access rules from `config/emote/emotes.json`.<br>
Permissions are resolved through a Fabric Permissions API-compatible mod such as LuckPerms.

## Permission types

| Permission        | Purpose                                                             | Default                          |
|-------------------|---------------------------------------------------------------------|----------------------------------|
| `emote.manage`    | Can use admin command                                               | Game-master permission level     |
| `emote.bypass`    | Ignore selection and playback policy for administration and testing | Denied                           |
| `emote.default`   | Built-in group used by `emotes.json`                                | Allowed unless explicitly denied |
| Custom permission | Server-defined groups such as VIP, supporter, or administrator      | Denied unless granted            |

`emote.manage` does not grant emote access. Grant `emote.bypass` or add a permission entry containing `"emotes": ["*"]` when an administrator also needs every emote.

---

## Playback policy

| Playback source    |               Permission                |               Standalone                |                 Disable                 |                Cooldown                 |
|--------------------|:---------------------------------------:|:---------------------------------------:|:---------------------------------------:|:---------------------------------------:|
| Wheel and commands | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> |
| Idle               | <span style="color: #ef5350">No</span>  | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> | <span style="color: #4caf50">Yes</span> |
| API                | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  |
| emote.bypass       | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  | <span style="color: #ef5350">No</span>  |

API and bypass playback must still meet the minimum playback requirements.

---

## `emotes.json`

```json
{
  "schema_version": 3,
  "disabled": ["example:broken"],
  "permissions": [
    {
      "permission": "emote.default",
      "emotes": ["example:wave", "example:hello"]
    },
    {
      "permission": "emote.vip",
      "emotes": ["example:(dance|sit)"]
    },
    {
      "permission": "emote.admin",
      "emotes": ["*"]
    }
  ]
}
```

| Field            | Behavior                                                                          |
|------------------|-----------------------------------------------------------------------------------|
| `schema_version` | Must be `3`. Version `2` is upgraded automatically.                              |
| `disabled`       | Hidden from normal players. Only players with `emote.bypass` can use them.        |
| `permissions`    | Permission groups used to assemble each player's available emote IDs.             |
| `permission`     | Permission checked through the installed permission provider.                     |
| `emotes`         | Valid emote IDs match literally. Other entries are full Java regular expressions. `"*"` grants every enabled standalone emote. |
| `idle`           | Optional idle-playback rule. See [Idle Emotes](advanced-usage.md#idle-emotes).    |

