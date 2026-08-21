# Home

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/15c895aea280b546764a0b7f2db2a4cb1f9628c8.gif)

> Thanks to [Popular Vibe](https://block-display.com/bd/77774) for allowing us to use the animation.

[![Web converter](https://img.shields.io/badge/Web_converter-0067C0?style=flat-square&logo=githubpages&logoColor=white)](https://hanhy06.github.io/emote/converter/)
[![Modrinth](https://img.shields.io/badge/Modrinth-00AF5C?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/mod/emote)
[![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/hanhy06/emote)
[![Discord](https://img.shields.io/badge/Discord-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/CRWqKbSebW)

Emote is a server-side Fabric mod that plays animations using Minecraft display entities. The server administration guides cover installing emotes, permissions, disabling emotes, cooldowns, and using Sequences. The web converter for creating Animations is available from the badge above.

## Troubleshooting

| Problem | What to check |
|---|---|
| An emote does not appear in the list | Check the server log, duplicate IDs, the `disabled` list, the `standalone` setting, and permissions. |
| A JSON file does not load | Check `schema_version`, required fields, time strings, and the file size limit. |
| The player skin is not applied | Check the skin-part assignments in the converter and `mineskin_api_key`. Try playing the emote again after the new skin finishes processing. |
| The skin is applied in the wrong position | Reassign the part and order of each node. For two-player animations, also check the `initiator` and `partner` spaces. |
| A Sequence does not load | Check that each referenced Animation exists and that reused node IDs, skin layouts, lifecycle events, and playback modes are compatible. |
| A partner does not connect to a cooperative emote | Check the distance, height difference, facing directions, line of sight, and whether both players are in the same dimension. |

If the problem persists, report it through [GitHub Issues](https://github.com/hanhy06/emote/issues) or [Discord](https://discord.gg/CRWqKbSebW) with reproduction steps, the Emote and Minecraft versions, and the relevant server logs.
