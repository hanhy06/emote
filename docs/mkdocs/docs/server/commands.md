# Commands

## Player commands

| Command | Description |
|---|---|
| `/emote` | Opens the menu of available emotes. |
| `/emote <page>` | Opens the specified menu page. |
| `/emote search [query] [page]` | Searches available emotes by name, ID, and description. |
| `/emote play <id>` | Plays an emote by its exact ID. |
| `/emote stop` | Stops your current emote. |
| `V` | Opens the emote wheel when the client mod is installed. |

The menu, search results, suggestions, and wheel only show emotes that the player can use and that have `standalone: true`. Players with `emote.bypass` can also see disabled, unassigned, and `standalone: false` emotes for administration and testing.

## Management commands

The following commands require `emote.manage`. If the permission provider does not return an explicit value, they are available to operators with the game-master permission level.

| Command | Description |
|---|---|
| `/emote list` | Lists loaded file and API emotes regardless of permissions or `disabled`. |
| `/emote reload` | Reloads the configuration and emotes, stopping all current playback. |
| `/emote enable <id>` | Removes an ID from `disabled` and reloads. |
| `/emote disable <id>` | Adds a file-emote ID to `disabled` and reloads. |
| `/emote stop <player>` | Stops the specified player's emote. |
| `/emote stop-all` | Stops every player's emote. |
| `/emote stress-test [count]` | Starts a performance measurement with the specified number of instances. |
| `/emote stress-test stop` | Stops the active performance measurement. |

`/emote disable` only targets file emotes loaded from `animations/`. It cannot disable emotes registered at runtime through the API.
