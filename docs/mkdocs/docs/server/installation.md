# 설치와 설정 파일

## 파일 배치

서버의 Minecraft 및 Fabric Loader 버전에 맞는 Emote 모드 파일을 `mods/`에 넣습니다. 서버를 처음 실행하면 다음 구조가 만들어집니다.

```text
config/emote/
├── config.json
├── emotes.json
└── animations/
```

웹 변환기에서 내보낸 Animation 또는 Sequence JSON은 `animations/` 아래에 넣습니다. 하위 디렉터리도 함께 읽으므로 제작자나 팩 단위로 나누어도 됩니다.

```text
animations/
├── default/
│   ├── wave.json
│   └── dance.json
└── idle/
    ├── sit-down.json
    ├── sit-idle.json
    └── sit-sequence.json
```

파일 이름은 관리 용도일 뿐입니다. 실제 이모트 ID는 JSON 안의 `id`를 사용합니다. 서로 다른 파일이 같은 ID를 선언하면 해당 ID의 파일을 모두 거부합니다.

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

| 필드 | 동작 |
|---|---|
| `menu_page_size` | `/emote` 메뉴 한 페이지에 표시할 이모트 수입니다. 최소 `1`입니다. |
| `mineskin_api_key` | 플레이어 스킨을 디스플레이용 텍스처로 변환할 때 사용합니다. 빈 문자열이면 플레이어 스킨 적용을 사용하지 않습니다. |
| `mineskin_poll_interval_seconds` | MineSkin 작업 상태 확인 간격입니다. `1`~`60`초입니다. |
| `mineskin_cache_retention_days` | 스킨 캐시 보존 기간입니다. `1`~`3650`일입니다. |
| `mineskin_cache_max_mib` | 스킨 캐시 최대 크기입니다. MiB 단위입니다. |
| `max_active_display_entities` | 서버 전체에서 Emote가 활성화할 수 있는 디스플레이 엔티티 상한입니다. `0`이면 생성할 수 없습니다. |

## 다시 불러오기

파일을 수정하거나 추가한 뒤 실행합니다.

```text
/emote reload
```

다시 불러오면 다음 작업이 함께 일어납니다.

1. `config.json`과 `emotes.json`을 다시 읽습니다.
2. 현재 재생 중인 모든 이모트를 중지합니다.
3. `animations/`의 Animation과 Sequence를 다시 구성합니다.
4. 접속 중인 클라이언트의 휠 목록을 동기화합니다.

잘못된 Animation 파일은 개별적으로 건너뜁니다. 잘못된 `config.json` 또는 `emotes.json`은 현재 메모리의 정상 설정을 유지하며 서버 로그에 원인을 남깁니다.

## 설치 확인

```text
/emote list
```

이 명령은 로드된 ID, 이름, 노드 수, 재생 시간, 원본 파일, 반복 방식과 플레이어 표시 여부를 보여줍니다. 목록에 존재하지만 플레이어에게 보이지 않는다면 파일 로드보다 [접근 제어](access-control.md)를 먼저 확인합니다.
