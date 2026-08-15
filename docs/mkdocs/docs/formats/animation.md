# Animation 포맷

Animation 파일은 스키마 버전 `3`을 사용하며 하나의 이모트에 필요한 디스플레이 엔티티, 변환 행렬 및 명령을 정의합니다.

```json
{
  "type": "animation",
  "schema_version": 3,
  "id": "example:wave",
  "metadata": {
    "name": "Wave",
    "description": "A short wave."
  },
  "settings": {
    "standalone": true,
    "cooldown": "2s",
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
    },
    "playback": {
      "mode": "once",
      "loop_delay": "0t"
    }
  },
  "nodes": {},
  "timeline": {
    "duration": "20t",
    "keyframes": [],
    "events": {}
  }
}
```

모든 노드와 이벤트 형태를 포함한 [Animation 참조 JSON](reference/animation.json)도 함께 제공합니다.

## 루트 필드

| 필드 | 설명 |
|---|---|
| `type` | 반드시 `animation`이어야 합니다. |
| `schema_version` | 반드시 `3`이어야 합니다. |
| `id` | `namespace:path` 형식의 소문자 Minecraft 식별자입니다. |
| `metadata` | 표시 이름, 설명 및 사용자 정의 메타데이터입니다. |
| `settings` | 선택 노출, 플레이어 동작 및 재생 설정입니다. |
| `nodes` | 안정적인 노드 ID를 키로 사용하는 디스플레이 엔티티와 명령 앵커입니다. |
| `timeline` | 재생 시간, 변환, 표시 상태 및 명령 이벤트입니다. |

Animation JSON 파일은 최대 8 MiB, 타임라인은 최대 10분으로 제한됩니다.

## 시간 값

게임 시간은 Minecraft 시간 단위를 사용하는 문자열로 작성합니다.

| 예 | 의미 |
|---|---|
| `"1d"` | Minecraft 하루 |
| `"5s"` | 5초 |
| `"20"`, `"20t"` | 20틱. `t`는 생략할 수 있습니다. |

이 형식은 `cooldown`, `loop_delay`, 타임라인과 이벤트의 `time`, `duration`, `interpolation_duration`에 사용됩니다.

## 메타데이터

- `name`: 명령과 이모트 UI에 표시할 이름입니다.
- `description`: 플레이어에게 표시할 설명입니다.
- 그 밖의 필드는 그대로 보존되어 API와 웹 변환기에 노출됩니다.

## 설정

### 선택 노출과 재사용 대기시간

`standalone`은 애니메이션이 메뉴, 휠, 검색 및 명령 제안에 나타나고 직접 재생될 수 있는지를 결정합니다. Sequence 안에서만 사용할 애니메이션은 `false`로 설정합니다.

`cooldown`은 애니메이션이 성공적으로 시작된 뒤 플레이어에게 적용됩니다.

### 플레이어 동작

- `hidden`: 재생 중 원래 플레이어를 숨깁니다.
- `movement_distance`: 플레이어가 지정한 수평 거리만큼 이동하면 재생을 중단합니다. `0`은 비활성화입니다.
- `jump`, `submerge`, `ride`, `damage`, `attack`, `game_mode_change`: 해당 행동이 발생하면 재생을 중단합니다.

### 재생 방식

| 모드 | 설명 |
|---|---|
| `once` | 타임라인을 한 번 재생합니다. `loop_delay`는 `0t`여야 합니다. |
| `hold` | 한 번 재생한 뒤 중지될 때까지 마지막 프레임을 유지합니다. `loop_delay`는 `0t`여야 하며 Sequence에서 사용할 수 없습니다. |
| `loop` | `loop_delay` 후 타임라인을 반복합니다. |
| `server_sync` | 서버 시간에 맞춰 재생합니다. Sequence에서 사용할 수 없습니다. |

## 노드

`nodes`의 각 속성 이름은 안정적인 노드 ID입니다. 모든 노드는 `space`와 행 우선 순서의 숫자 16개로 이루어진 `default_matrix`를 가져야 합니다.

| 공간 | 2인 재생에서 사용하는 루트 |
|---|---|
| `scene` | 시작 플레이어가 만든 공유 장면 루트 |
| `initiator` | Sequence `participants`에 정의한 시작 플레이어 배치 |
| `partner` | Sequence `participants`에 정의한 상대 플레이어 배치 |

단독 재생과 1인 Sequence에서는 세 공간 모두 같은 플레이어 루트를 사용합니다. 차이는 2인 Sequence에서 나타납니다.

| 타입 | 필수 필드 | 용도 |
|---|---|---|
| `item_display` | `item_stack_snbt`, `item_display` | 아이템 스택 표시 |
| `block_display` | `block_state_snbt` | 블록 상태 표시 |
| `text_display` | `text` | Minecraft 텍스트 컴포넌트 표시 |
| `anchor` | `type`, `default_matrix` 외 없음 | 엔티티를 만들지 않고 명령 실행 위치 제공 |

디스플레이 노드는 다음 필드도 지원합니다.

- `visible`: 기본값은 `true`입니다.
- `entity_nbt`: 추가 디스플레이 엔티티 SNBT입니다.
- `skin`: 아이템 디스플레이에 플레이어 스킨을 연결합니다.

`skin`에는 `participant`, 플레이어 신체 `part`, 0 이상의 `order`가 필요합니다. `participant`는 `initiator` 또는 `partner`이며 노드의 `space`와 일치해야 합니다. `scene` 노드는 스킨 연결을 지원하지 않습니다.

지원하는 파츠는 `head`, `body`, `left_arm`, `right_arm`, `left_leg`, `right_leg`입니다. 같은 참여자와 파츠에 연결된 노드는 `order` 순서대로 스킨이 적용됩니다.

## 타임라인

`duration`은 전체 재생 시간을 지정합니다. `keyframes`는 `time` 순서로 정렬해야 합니다.

키프레임은 다음 값을 포함할 수 있습니다.

- `node_transforms`: 노드 ID와 `matrix`의 매핑
- `node_states`: 디스플레이 노드 ID와 `visible` 값의 매핑
- `interpolation_duration`: 개별 변환에서 덮어쓰지 않았을 때 사용할 보간 시간

보간 시간은 해당 노드의 이전 변환과 현재 키프레임 사이를 벗어날 수 없습니다. Anchor 노드는 변환을 지원하지만 표시 상태는 지원하지 않습니다.

## 명령 이벤트

선택적인 `timeline.events`는 네 이벤트 그룹을 지원합니다.

| 이벤트 | 실행 시점 |
|---|---|
| `start` | 재생 시작 시 |
| `timeline` | 지정한 `time` |
| `loop` | 각 반복 완료 후 |
| `stop` | 재생 중지 시 |

각 이벤트는 `source`, `origin`, `commands` 배열을 가집니다. `source`는 `player`, `server` 또는 노드 이름입니다. `origin`은 애니메이션 `root` 또는 노드 이름이며, 선택적으로 숫자 3개의 `offset`을 사용할 수 있습니다.

타임라인 이벤트는 시간순으로 정렬해야 하며 타임라인 종료 시간보다 앞에 있어야 합니다.

## 스키마 1에서 이전

웹 변환기는 공개된 스키마 1 Animation을 불러와 스키마 3으로 내보낼 수 있습니다. 서버는 스키마 3만 직접 불러옵니다.

| 스키마 1 | 스키마 3 |
|---|---|
| `type` 없음 | `type: "animation"` 필수 |
| `minecraft_version` | 런타임 파일에서 제거 |
| `tick_rate: 20` | Minecraft의 20 TPS가 암시되므로 제거 |
| `transform_space` | 제거. 행렬은 루트 로컬, 숫자 16개, 행 우선 순서를 유지 |
| 루트 `standalone` | `settings.standalone` |
| 재사용 대기시간 없음 | `settings.cooldown`, 이전 시 `"0t"` 사용 |
| 루트 `player` | `settings.player` |
| `timeline.loop` | `settings.playback.mode` |
| `timeline.loop_delay_ticks` | `settings.playback.loop_delay` |

틱 숫자 필드는 Minecraft 시간 문자열로 변경합니다.

| 스키마 1 | 스키마 3 |
|---|---|
| `duration_ticks: 40` | `duration: "40t"` |
| `tick: 10` | `time: "10t"` |
| `interpolation_duration_ticks: 2` | `interpolation_duration: "2t"` |

스키마 3 노드는 참여자 소유권도 지원합니다. 플레이어 신체 노드는 `space: "initiator"`, 공유 소품과 명령 앵커는 `space: "scene"`을 사용합니다. 기존 스킨 연결에는 `participant: "initiator"`를 지정합니다. 두 번째 플레이어를 위해 명시적으로 제작한 노드만 두 필드에 `partner`를 사용합니다.
