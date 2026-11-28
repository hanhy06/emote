# Troubleshooting

## An emote is missing from the list

Run `/emote reload` and check its output and the server log for file loading errors or duplicate IDs. If the file loaded, check the `disabled` list in `config/emote/emotes.json`, the player's permissions, and the Animation's `settings.standalone` setting. [Access Control](access-control.md)

## A skin message appears when playing an emote

Find the message you saw in chat in the table below.

| Message | What to check |
| --- | --- |
| `Preparing your skin… N%` | Emote is preparing your skin. Wait a moment, then try playing the emote again. |
| `No complete player or default skin is available.` | Neither the player skin nor the emote's default skin has all the parts the emote needs. Try playing the same emote with a regular Minecraft account that has a skin. Offline mode and development accounts such as `Player123` may give the server a profile without skin data. |
| `Your skin could not be prepared.` | Emote failed to bake the player skin. If the emote has a prepared default skin, it can play with that skin. Look for `Skin bake failed` in the server log. |
| `[Emote] No bake accounts or MineSkin API key configured; only cached skin textures are available` | This message appears once each time you join if the server has no registered Minecraft accounts for skin baking and no MineSkin API key. The emote can still play if the required textures are already cached. |

If playback fails for only one emote, start by checking `skin.default` in that emote's JSON file. To set a server-wide default skin, set `default_skin` in `config/emote/config.json` to the name of a Minecraft player who has a skin, then run `/emote reload`. The default skin must have all the textures the emote needs. [Server configuration](installation.md) · [Default skin](../developers/animation.md#skin)

## The player skin is missing or misaligned

- If the **skin is missing**, check that each part of the model has the correct skin part assigned in the web converter. Check the server's skin baking settings and try playing the emote again after skin preparation finishes.
- If the **skin is misaligned**, check which skin part is assigned to the affected node in the web converter. Correct the assignment and the order of any nodes that share that skin part as needed.

## An emote file fails to load

| Symptom | What to check |
| --- | --- |
| A JSON file fails to load. | Check the file and error in the server log, then verify `schema_version`, the required fields, and the format of time values. The size limit is 8 MiB per emote JSON file. [Animation format](../developers/animation.md) · [Sequence format](../developers/sequence.md) |
| A Sequence fails to load. | Make sure the server loaded every Animation the Sequence references. Sequences cannot use Animations in `hold` or `server_sync` mode. If multiple Animations reuse a node ID, the attachment settings and player skin binding must match for that node. [Animation compatibility](../developers/sequence.md#animation-compatibility) |

## Check the server log for skin errors

Check the server console or `logs/latest.log` in the server directory around the time you joined, tried to play the emote, or ran `/emote reload`. Search for the messages below.

| Log message | Level | What it means |
| --- | --- | --- |
| `Skin bake failed for` | `WARN` | Skin baking failed. The warning and the exception that follows it show the cause. Check earlier log entries too, because skin preparation can start as soon as a player joins. |
| `MineSkin rejected baked texture` | `WARN` | MineSkin rejected the skin baking request. Its response appears on the same line. |
| `Could not resolve player skin` | `WARN` | An exception occurred while reading the skin from the player's profile. If the profile has no skin data, the playback error may appear in chat without this warning. |
| `Could not prepare default skin for` | `WARN` | Emote could not prepare the skin specified in `default_skin`. It continues to use any default skins already stored in the emote JSON files. |
| `Could not save default skin in` | `WARN` | Emote could not save the prepared default skin to the emote file. Check the path and write permissions for the file named in the log. |
| `No bake accounts or MineSkin API key configured` | `ERROR` | Emote logs this at server startup or after a configuration reload if it has no available way to upload skins. Run `/emote account` to check which accounts are registered and whether any show `login required`. If no accounts are registered, check `mineskin_api_key`. |

Playback can still fail if you saw no warning when joining. A player profile can lack skin textures, and a baking request can fail even with an API key configured. Emote uses registered bake accounts first. If an account shows `login required`, sign in again with `/emote account login`.

## Get help

If the problem persists, report it on GitHub or Discord and include the information below. [GitHub Issues](https://github.com/hanhy06/emote/issues) · [Discord](https://discord.gg/CRWqKbSebW)

- The Minecraft and Emote versions and the name of the affected emote.
- The steps to reproduce the problem.
- The chat message and server log entries from the same time. If an exception follows, include the `Caused by:` lines. Redact any API keys or login codes before posting.
