# Permissions and Access Control

Emote's access policy is defined in `config/emote/emotes.json`. Actual permissions are granted through a Fabric Permissions API-compatible permission mod such as LuckPerms.

## Permission types

| Permission | Purpose | Default |
|---|---|---|
| `emote.manage` | List, reload, enable, disable, stop others, stop all, and stress-test commands | Allowed for operators with the game-master permission level |
| `emote.bypass` | Play while ignoring server policy | Never granted automatically |
| `emote.default` | Default grant group in `emotes.json` | Allowed for every player unless overridden by a permission mod |
| Custom permission | Server-specific emote groups such as VIP, supporter, or administrator | Granted through the permission mod |

Example LuckPerms commands:

```text
/lp group admin permission set emote.manage true
/lp group vip permission set emote.vip true
/lp user <player> permission set emote.bypass true
```

`emote.manage` only grants management commands. An operator with this permission does not automatically gain access to every emote. To grant all emotes as well, set `"emotes": ["*"]` for a separate group or grant `emote.bypass`.

See the [LuckPerms Wiki](https://luckperms.net/wiki/Home) for detailed usage.

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

Access is evaluated in this order:

1. Allow if the player has `emote.bypass`.
2. Deny if the ID is in `disabled`.
3. Allow if any permission group held by the player contains the ID or `*` in `emotes`.
4. Deny if no rule matches.

Disallowed emotes do not appear in menus, searches, command suggestions, or the client wheel, and cannot be played by entering their ID directly.

!!! note "`emote.default` is a regular permission"
    `emote.default` is merely allowed by default. If the permission mod explicitly gives a player `emote.default=false`, that player cannot use the default list either.

## How `disabled` works

`disabled` does not delete files or exclude them from loading. Emote definitions remain loaded, but normal players cannot select or play them directly.

```text
/emote disable example:dance
```

This command:

1. Saves `example:dance` to `disabled` in `emotes.json`.
2. Stops current playback of that ID.
3. Reloads the entire Emote configuration, which also stops other emotes currently playing.
4. Updates player menus and wheel lists.

To allow it again, use:

```text
/emote enable example:dance
```

Players with `emote.bypass` can still see and play disabled emotes.

Disabling an Animation does not prevent another Sequence from referencing it as an internal step. To block the entire Sequence, add the Sequence's own ID to `disabled`.

## What `emote.bypass` does not bypass

`emote.bypass` is not a higher form of administrator permission. These restrictions still apply:

- Nonexistent IDs cannot be played.
- Animations with `standalone: false` cannot be played directly.
- Invalid Animations or Sequences that failed to load cannot be used.
- Display-entity limits and playback-start failures are not ignored.
- Management commands that require `emote.manage` are not granted.
