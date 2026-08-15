# 명령어

## 플레이어 명령

| 명령 | 설명 |
|---|---|
| `/emote` | 사용할 수 있는 이모트 메뉴를 엽니다. |
| `/emote <page>` | 지정한 메뉴 페이지를 엽니다. |
| `/emote search [query] [page]` | 이름, ID와 설명으로 사용 가능한 이모트를 검색합니다. |
| `/emote play <id>` | 정확한 ID로 이모트를 재생합니다. |
| `/emote stop` | 자신의 현재 이모트를 중지합니다. |
| `V` | 클라이언트 모드를 설치한 경우 이모트 휠을 엽니다. |

메뉴, 검색, 제안과 휠에는 해당 플레이어가 사용할 수 있고 `standalone: true`인 이모트만 나타납니다.

## 관리 명령

다음 명령에는 `emote.manage`가 필요합니다. 권한 제공자가 명시적인 값을 주지 않으면 게임 마스터 등급 운영자에게 허용됩니다.

| 명령 | 설명 |
|---|---|
| `/emote list` | 권한이나 `disabled`와 무관하게 로드된 파일 및 API 이모트를 표시합니다. |
| `/emote reload` | 설정과 이모트를 다시 읽고 현재 재생을 모두 중지합니다. |
| `/emote enable <id>` | ID를 `disabled`에서 제거하고 다시 불러옵니다. |
| `/emote disable <id>` | 파일 이모트 ID를 `disabled`에 추가하고 다시 불러옵니다. |
| `/emote stop <player>` | 지정한 플레이어의 이모트를 중지합니다. |
| `/emote stop-all` | 모든 플레이어의 이모트를 중지합니다. |
| `/emote stress-test [count]` | 지정한 수의 인스턴스로 성능 측정을 시작합니다. |
| `/emote stress-test stop` | 진행 중인 성능 측정을 중지합니다. |

`/emote disable`은 `animations/`에서 불러온 파일 이모트만 대상으로 합니다. API에서 런타임 등록한 이모트는 이 명령으로 비활성화하지 않습니다.
