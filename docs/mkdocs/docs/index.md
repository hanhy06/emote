# Home

<iframe
    width="720"
    height="405"
    src="https://www.youtube.com/embed/miEEutsojFw?list=PLXqBJ1wZw7O0"
    title="YouTube video player"
    frameborder="0"
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
    allowfullscreen>
</iframe>

[![Web converter](https://img.shields.io/badge/Web_converter-0067C0?style=flat-square&logo=githubpages&logoColor=white)](https://docs.hanhy06.kr/emote/converter/)
[![Wiki](https://img.shields.io/badge/Wiki-9d4edd?style=flat-square&logo=materialformkdocs&logoColor=white)](https://docs.hanhy06.kr/emote/)
[![Modrinth](https://img.shields.io/badge/Modrinth-00AF5C?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/mod/emote)
[![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/hanhy06/emote)
[![Discord](https://img.shields.io/badge/Discord-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/CRWqKbSebW)

Emote is a server-side Fabric mod that plays animations using Minecraft display entities. The server administration guides cover installing emotes, permissions, disabling emotes, cooldowns, and using Sequences. The web converter for creating Animations is available from the badge above.

## Troubleshooting

| Problem                                           | What to check                                                                                                                                |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| An emote does not appear in the list              | Check the server log, duplicate IDs, the `disabled` list, the `standalone` setting, and permissions.                                         |
| A JSON file does not load                         | Check `schema_version`, required fields, time strings, and the file size limit.                                                              |
| The player skin is not applied                    | Check the skin-part assignments in the converter and the skin provider configuration. Try playing the emote again after skin processing finishes. |
| The skin is applied in the wrong position         | Reassign the part and order of each node. |
| A Sequence does not load                          | Check that each referenced Animation exists and that reused node IDs, skin layouts, and playback modes are compatible.                       |

If the problem persists, report it through [GitHub Issues](https://github.com/hanhy06/emote/issues) or [Discord](https://discord.gg/CRWqKbSebW) with reproduction steps, the Emote and Minecraft versions, and the relevant server logs.
