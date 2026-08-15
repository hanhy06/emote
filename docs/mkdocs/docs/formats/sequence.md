# Sequence 포맷

Sequence 파일은 스키마 버전 `3`을 사용하며 기존 Animation을 연결해 하나의 이모트를 만듭니다.

```json
{
  "type": "sequence",
  "schema_version": 3,
  "id": "example:sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down, wait, and stand up."
  },
  "settings": {
    "cooldown": "5s",
    "player": {
      "hidden": true,
      "stop_conditions": {
        "movement_distance": 0.1,
        "jump": true,
        "submerge": true,
        "ride": true,
        "damage": true,
        "attack": true,
        "game_mode_change": true
      }
    }
  },
  "steps": [
    {"emote": "example:sit_down"},
    {"wait": "10t"},
    {"emote": "example:sit_idle", "repeat": 3},
    {"emote": "example:stand_up"}
  ]
}
```

가중치 무작위 선택을 포함한 [Sequence 참조 JSON](reference/sequence.json)과 [2인 Sequence 참조 JSON](reference/two-player-sequence.json)도 함께 제공합니다.

## 루트 필드

| 필드 | 설명 |
|---|---|
| `type` | 반드시 `sequence`여야 합니다. |
| `schema_version` | 반드시 `3`이어야 합니다. |
| `id` | `namespace:path` 형식의 소문자 Minecraft 식별자입니다. |
| `metadata` | 표시 이름, 설명 및 사용자 정의 메타데이터입니다. |
| `participants` | 2인 Sequence에 필요한 참여자 배치입니다. 1인 Sequence에서는 생략합니다. |
| `settings` | Sequence 전체의 재사용 대기시간과 플레이어 동작입니다. |
| `steps` | Animation, 대기 또는 하나의 `await_partner` 단계입니다. |

Sequence JSON 파일은 최대 8 MiB로 제한됩니다.

!!! tip inline end "시간 단위"
    Emote는 Minecraft 시간 포맷을 사용합니다.<br>
    `1s`는 `20t`입니다.

    `s`: 초<br>
    `t` 또는 생략: 틱<br>
    `d`: Minecraft 하루

## 메타데이터와 설정

- `metadata.name`: 명령과 이모트 UI에 표시할 이름입니다.
- `metadata.description`: 플레이어에게 표시할 설명입니다.
- 추가 메타데이터는 보존되어 API와 웹 변환기에 노출됩니다.
- `settings.cooldown`: Sequence가 성공적으로 시작된 뒤 적용할 재사용 대기시간입니다.
- `settings.player`: Sequence 전체에 적용할 플레이어 표시 및 중단 조건입니다. 참조한 Animation의 플레이어 설정을 대체합니다.

중단 조건의 각 필드는 [Animation 포맷](animation.md)의 플레이어 동작 설정과 같습니다.

## Animation 단계

`emote`로 Animation 하나를 지정하고 선택적으로 `repeat`를 사용합니다.

```json
{"emote": "example:sit_idle", "repeat": 3}
```

`repeat` 기본값은 `1`입니다. 한 번 반복할 때 Animation의 완전한 재생 주기 하나를 실행합니다. 반복 Animation은 각 주기 사이에 `loop_delay`를 포함합니다.

참조한 Animation은 로드되어 있고 활성화되어야 합니다. `standalone: false`인 Animation도 사용할 수 있지만 다른 Sequence 또는 `server_sync` Animation은 참조할 수 없습니다.

## 무작위 선택

ID 배열을 사용하면 각 반복마다 같은 확률로 하나를 선택합니다.

```json
{
  "emote": [
    "example:sit_idle_1",
    "example:sit_idle_2",
    "example:sit_idle_3"
  ],
  "repeat": 3
}
```

명시적인 확률은 ID와 정수 가중치를 번갈아 작성합니다. 가중치의 합은 `100`이어야 합니다.

```json
{
  "emote": [
    "example:sit_idle_1", 30,
    "example:sit_idle_2", 40,
    "example:sit_idle_3", 30
  ],
  "repeat": 3
}
```

각 반복마다 후보를 다시 선택합니다. Animation 후보가 여러 개라면 직전에 선택한 Animation을 제외하고 남은 확률을 자동으로 정규화합니다. Sequence 제어 ID는 Animation 후보 개수에 포함하지 않습니다.

## 반복 제어

두 예약 ID로 현재 Animation 단계의 반복을 제어할 수 있습니다.

| ID | 동작 |
|---|---|
| `emote:continue` | Animation과 `loop_delay`를 추가하지 않고 현재 반복을 소비한 뒤 다음 반복을 선택합니다. |
| `emote:break` | 현재 반복 루프를 끝내고 다음 Sequence 단계로 이동합니다. |

```json
{
  "emote": [
    "example:sit_idle_1", 50,
    "example:sit_idle_2", 30,
    "emote:continue", 15,
    "emote:break", 5
  ],
  "repeat": 10
}
```

`emote:continue`는 연속으로 선택될 수 있습니다. `emote:break`는 현재 Animation 단계만 끝내며 Sequence나 협동 분기 전체를 끝내지 않습니다. Sequence에는 실제 Animation 후보가 최소 하나 있어야 하며 협동 제안 Animation에는 제어 ID를 사용할 수 없습니다.

## 대기 단계

```json
{"wait": "10t"}
```

대기 단계는 첫 단계, 마지막 단계 또는 연속된 단계가 될 수 없으며 `repeat`와 함께 사용할 수 없습니다.

## Animation 호환성

한 Sequence에서 후보로 사용하는 모든 Animation은 같은 노드 ID와 호환되는 노드 내용을 가져야 합니다. 제어 ID는 호환성 검사에서 제외됩니다.

- 노드 타입이 같아야 합니다.
- 아이템 스택, 표시 컨텍스트, 블록 상태, 텍스트 및 엔티티 NBT가 같아야 합니다.
- 플레이어 스킨 파츠와 순서가 같아야 합니다.

기본 변환과 표시 여부는 달라도 됩니다. 각 Animation 단계가 시작될 때 해당 값으로 초기화됩니다.

타임라인 명령 이벤트는 유지됩니다. Sequence에서 참조하는 Animation은 `start`, `loop`, `stop` 명령 이벤트를 사용할 수 없습니다.

## 재생 동작

이모트를 다시 불러올 때 참조한 Animation을 해석하고 호환성을 검사합니다. 재생 전에는 선택된 단계들을 하나의 Animation으로 컴파일합니다. 디스플레이 엔티티는 한 번 생성되고 Sequence가 끝날 때까지 재사용됩니다.

Sequence의 플레이어 설정은 참조한 Animation의 플레이어 설정을 대체합니다. Sequence가 중지되거나 방해받으면 남은 단계도 모두 취소됩니다.

## 2인 Sequence

2인 Sequence는 루트에 `participants`를 추가하고 하나의 `await_partner` 단계를 가집니다.

```json
{
  "participants": {
    "initiator": {
      "position": "~ ~ ~",
      "rotation": "~ 0"
    },
    "partner": {
      "position": "^ ^ ^1.2",
      "rotation": "~180 0"
    }
  },
  "steps": [
    {
      "await_partner": {
        "emote": "emote:handshake_offer",
        "timeout": "10s"
      },
      "matched": [
        {"emote": "emote:handshake", "repeat": 2},
        {"wait": "1s"},
        {"emote": "emote:handshake_close"}
      ],
      "timeout": [
        {"emote": "emote:handshake_close"}
      ]
    }
  ]
}
```

`participants`에는 `initiator`와 `partner`를 모두 정의해야 합니다. 위치는 Minecraft 상대 좌표를 사용합니다. 세 성분 모두 `~` 또는 `^`를 사용해야 하며 절대 좌표는 허용하지 않습니다. `~`는 장면 원점, `^`는 시작 플레이어의 수평 시선 방향을 기준으로 합니다. 회전은 Minecraft 회전 문법을 사용합니다.

Sequence에는 최상위 단계가 정확히 하나 있어야 하며 그 단계는 `await_partner`여야 합니다.

| 필드 | 설명 |
|---|---|
| `await_partner.emote` | 기다리는 동안 시작 플레이어가 재생할 제안 Animation |
| `await_partner.timeout` | 시간 초과 분기로 이동하기 전의 양수 시간 |
| `matched` | 상대가 참여한 뒤 재생할 비어 있지 않은 분기 |
| `timeout` | 아무도 참여하지 않았을 때 재생할 비어 있지 않은 분기 |

`await_partner` 단계에는 `repeat`를 사용할 수 없습니다. `matched`와 `timeout`은 일반 Animation 및 대기 단계 규칙을 따르지만 또 다른 `await_partner`를 포함할 수 없습니다.

### 상대 연결 조건

제안 Animation은 시작 플레이어에게 재생되며 연결 전까지 상대 공간은 숨겨집니다. 다른 플레이어가 같은 Sequence를 시작했을 때 다음 조건을 모두 만족하면 참여합니다.

- 두 플레이어가 살아 있고 같은 차원에 있음
- 수평 거리 2블록 이하, 수직 거리 1블록 이하
- 서로를 향한 각도가 각각 45도 이내
- 시작 플레이어에게 상대 플레이어가 보임

호환되는 제안이 여러 개라면 가장 가까운 시작 플레이어를 선택합니다. 참여 시 상대를 예약하고, 제안 Animation이 끝날 때 연결 조건을 다시 검사합니다. 예약이 무효가 되면 다른 상대가 참여하거나 시간이 초과될 때까지 계속 기다립니다.

### 대칭 및 비대칭 Animation

호환 Animation에 `partner` 노드가 하나도 없다면 모든 `initiator` 노드, 스킨 연결, 변환 트랙 및 표시 트랙을 상대용으로 자동 복제합니다. 복제본은 상대 루트를 사용하므로 `~180 0` 같은 회전으로 같은 로컬 Animation이 시작 플레이어를 향하게 할 수 있습니다.

`partner` 노드가 하나라도 있으면 명시적인 비대칭 Animation으로 처리하며 상대 노드를 자동 생성하지 않습니다.

## 스키마 1에서 이전

웹 변환기는 공개된 스키마 1 Sequence를 불러와 스키마 3으로 내보낼 수 있습니다. 서버는 스키마 3만 직접 불러옵니다.

| 스키마 1 | 스키마 3 |
|---|---|
| `schema_version: 1` | `schema_version: 3` |
| 루트 `player` | `settings.player` |
| 재사용 대기시간 없음 | `settings.cooldown`, 이전 시 `"0t"` 사용 |
| 스키마 1 Animation 참조 | 각 Animation을 스키마 3으로 변환 |

기존 Animation 단계, 반복, 균등 또는 가중 무작위 선택 구조는 유지됩니다. 스키마 3은 Minecraft 시간 문자열을 사용하는 대기 단계와 2인 Sequence를 추가합니다.
