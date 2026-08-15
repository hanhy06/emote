# Emote

![Emote demo](https://cdn.modrinth.com/data/qUF0jygw/images/15c895aea280b546764a0b7f2db2a4cb1f9628c8.gif)

> 애니메이션 사용을 허락해 주신 [Popular Vibe](https://block-display.com/bd/77774)에 감사드립니다.

[![Web converter](https://img.shields.io/badge/Web_converter-0067C0?style=flat-square&logo=githubpages&logoColor=white)](https://hanhy06.github.io/emote/converter/)
[![Modrinth](https://img.shields.io/badge/Modrinth-00AF5C?style=flat-square&logo=modrinth&logoColor=white)](https://modrinth.com/mod/emote)
[![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/hanhy06/emote)
[![Discord](https://img.shields.io/badge/Discord-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/CRWqKbSebW)

Emote는 Minecraft 디스플레이 엔티티로 애니메이션을 재생하는 서버 측 Fabric 모드입니다. 서버 운영 문서에서는 이모트 설치, 권한, 비활성화, 쿨다운과 Sequence 적용 방법을 설명합니다. Animation 제작 도구인 웹 변환기는 위 배지에서 별도로 열 수 있습니다.

## 문제 해결

| 문제 | 확인할 내용 |
|---|---|
| 이모트가 목록에 나타나지 않음 | 서버 로그, 중복 ID, `disabled`, `standalone` 설정 및 권한을 확인합니다. |
| JSON을 불러오지 못함 | `schema_version`, 필수 필드, 시간 문자열 및 파일 크기 제한을 확인합니다. |
| 플레이어 스킨이 적용되지 않음 | 변환기의 스킨 파츠 지정과 `mineskin_api_key`를 확인합니다. 새 스킨 처리가 끝난 뒤 다시 재생합니다. |
| 스킨이 잘못된 위치에 적용됨 | 각 노드의 파츠와 순서를 다시 지정합니다. 2인용 애니메이션은 `initiator`와 `partner` 공간도 확인합니다. |
| Sequence를 불러오지 못함 | 참조한 Animation의 존재 여부, 노드 호환성, `standalone`과 재생 모드를 확인합니다. |
| 협동 이모트 상대가 연결되지 않음 | 거리, 높이 차이, 서로 바라보는 방향, 시야 및 같은 차원인지 확인합니다. |

문제가 계속되면 재현 절차, Emote 버전, Minecraft 버전과 관련 서버 로그를 함께 준비해 [GitHub Issues](https://github.com/hanhy06/emote/issues) 또는 [Discord](https://discord.gg/CRWqKbSebW)에 제보합니다.
