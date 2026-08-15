# 서버 관리

## 명령어

### 플레이어

| 명령 | 설명 |
|---|---|
| `/emote` | 이모트 메뉴를 엽니다. |
| `/emote <page>` | 지정한 메뉴 페이지를 엽니다. |
| `/emote search [query] [page]` | 이모트를 검색합니다. |
| `/emote play <id>` | ID로 이모트를 재생합니다. |
| `/emote stop` | 현재 이모트를 중지합니다. |
| `V` | 클라이언트 이모트 휠을 엽니다. |

### 관리자

| 명령 | 설명 |
|---|---|
| `/emote list` | 불러온 이모트와 출처를 표시합니다. |
| `/emote reload` | 설정과 애니메이션을 다시 불러옵니다. |
| `/emote enable <id>` | 이모트를 활성화합니다. |
| `/emote disable <id>` | 이모트를 비활성화합니다. |
| `/emote stop <player>` | 특정 플레이어의 이모트를 중지합니다. |
| `/emote stop-all` | 모든 이모트를 중지합니다. |
| `/emote stress-test [count]` | 여러 이모트를 재생해 서버 성능을 측정합니다. |

관리 명령은 `emote.manage` 권한을 사용하며, 기본적으로 게임 마스터 등급 운영자에게 허용됩니다.

## `config.json`

```json
{
  "schema_version": 1,
  "menu_page_size": 6,
  "mineskin_api_key": "",
  "mineskin_poll_interval_seconds": 3,
  "mineskin_cache_retention_days": 30,
  "mineskin_cache_max_mib": 256,
  "max_active_display_entities": 512
}
```

`mineskin_api_key`를 설정하면 애니메이션에 플레이어 스킨을 적용할 수 있습니다. `max_active_display_entities`는 동시에 활성화할 수 있는 디스플레이 엔티티의 상한입니다.

## `emotes.json`

```json
{
  "schema_version": 2,
  "disabled": ["example:disabled"],
  "permissions": [
    {
      "permission": "emote.vip",
      "emotes": ["example:dance", "example:cry"],
      "idle": {
        "delay": "300s",
        "emote": ["example:dance", 70, "example:cry", 30]
      }
    },
    {
      "permission": "emote.default",
      "emotes": ["example:hello", "example:wave"]
    },
    {
      "permission": "emote.admin",
      "emotes": ["*"]
    }
  ]
}
```

- `disabled`는 지정한 ID를 비활성화합니다.
- `permissions`는 LuckPerms 권한별 사용 가능한 이모트와 유휴 이모트를 지정합니다.
- 모든 플레이어는 `emote.default` 항목을 적용받습니다.
- `*`는 활성화된 모든 이모트를 허용합니다.
- `emote.bypass`는 비활성화 목록, 권한 및 재사용 대기시간을 무시합니다.

유휴 이모트 배열에서 이모트 ID 다음의 숫자는 선택 가중치입니다. 가중치의 합은 `100`이어야 합니다.
