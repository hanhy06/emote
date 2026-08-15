# Sequence 사용

Sequence는 여러 Animation을 순서대로 연결해 플레이어에게는 하나의 이모트처럼 보여 줍니다. 서버 운영자는 Sequence JSON과 그 안에서 참조하는 Animation JSON을 모두 설치해야 합니다.

## 파일 구성 예

```text
config/emote/animations/sit/
├── sit-down.json
├── sit-idle.json
├── stand-up.json
└── sit.json
```

`sit.json`:

```json
{
  "type": "sequence",
  "schema_version": 3,
  "id": "example:sit",
  "metadata": {
    "name": "Sit",
    "description": "Sit down and stand up after waiting."
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
    {"emote": "example:sit-down"},
    {"emote": "example:sit-idle", "repeat": 3},
    {"emote": "example:stand-up"}
  ]
}
```

Sequence에 연결할 중간 Animation은 보통 직접 선택되지 않도록 설정합니다.

```json
"settings": {
  "standalone": false,
  "cooldown": "0t"
}
```

플레이어 권한과 쿨다운은 Sequence ID인 `example:sit`에 설정합니다. 내부 Animation ID를 플레이어의 `emotes` 목록에 지급할 필요는 없습니다.

```json
{
  "permission": "emote.default",
  "emotes": ["example:sit"]
}
```

## 설치 확인

1. 모든 JSON을 같은 서버의 `animations/` 아래에 넣습니다.
2. 파일을 다시 불러온 뒤 `/emote list`에서 Sequence와 참조 Animation이 모두 로드됐는지 확인합니다.
3. 일반 플레이어 권한으로 `/emote play example:sit`을 실행합니다.

Sequence가 로드되지 않으면 서버 로그에서 없는 Animation ID, 호환되지 않는 노드, 지원하지 않는 재생 모드 또는 잘못된 대기 단계 메시지를 확인합니다.

!!! note "완성된 예제 팩"
    바로 설치해 볼 수 있는 Sequence 예제 팩은 준비되는 대로 기존 `docs/example/` 예제 모음에 추가할 예정입니다. 현재 페이지의 JSON은 구조 설명용이며 참조 Animation 파일이 별도로 필요합니다.

무작위 선택, 대기, 반복 제어 및 2인 협동 Sequence를 직접 제작하려면 [Sequence 포맷 명세](../formats/sequence.md)를 참고합니다.
