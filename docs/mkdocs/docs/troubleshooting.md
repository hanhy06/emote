# 문제 해결

| 문제 | 확인할 내용 |
|---|---|
| 이모트가 목록에 나타나지 않음 | `/emote reload` 결과와 서버 로그, 중복 ID, `disabled`, `standalone` 설정 및 권한을 확인합니다. |
| JSON을 불러오지 못함 | `schema_version`, 필수 필드, 시간 문자열 및 파일 크기 제한을 확인합니다. |
| 플레이어 스킨이 적용되지 않음 | 변환기의 스킨 파츠 지정과 `mineskin_api_key`를 확인합니다. 새 스킨 처리가 끝난 뒤 다시 재생합니다. |
| 스킨이 잘못된 위치에 적용됨 | 각 노드의 파츠와 순서를 다시 지정합니다. 2인용 애니메이션은 `initiator`와 `partner` 공간도 확인합니다. |
| Sequence를 불러오지 못함 | 참조한 Animation의 존재 여부, 노드 호환성, `standalone`과 재생 모드를 확인합니다. |
| 협동 이모트 상대가 연결되지 않음 | 거리, 높이 차이, 서로 바라보는 방향, 시야 및 같은 차원인지 확인합니다. |

문제가 계속되면 재현 절차, Emote 버전, Minecraft 버전과 관련 서버 로그를 함께 준비해 [GitHub Issues](https://github.com/hanhy06/emote/issues) 또는 [Discord](https://discord.gg/CRWqKbSebW)에 제보합니다.
