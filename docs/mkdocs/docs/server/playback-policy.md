# 쿨다운과 유휴 이모트

## 이모트 쿨다운

쿨다운은 `emotes.json`에서 주는 것이 아니라 각 Animation 또는 Sequence JSON의 `settings.cooldown`에서 설정합니다.

```json
{
  "settings": {
    "cooldown": "5s"
  }
}
```

!!! tip inline end "시간 단위"
    Emote는 Minecraft 시간 포맷을 사용합니다.<br>
    `1s`는 `20t`입니다.

    `s`: 초<br>
    `t` 또는 생략: 틱<br>
    `d`: Minecraft 하루

쿨다운은 플레이어와 이모트 ID별로 따로 기록됩니다. `example:wave`를 사용해도 `example:dance`의 쿨다운에는 영향을 주지 않습니다.

쿨다운은 재생이 성공적으로 시작된 시점에만 적용됩니다. 권한 부족, 스킨 준비, 엔티티 상한 또는 다른 모드의 취소로 시작하지 못했다면 쿨다운도 시작하지 않습니다.

Sequence를 재생할 때는 Sequence 자신의 `settings.cooldown`만 적용합니다. 내부에서 참조한 Animation의 쿨다운은 합산하지 않습니다.

## 유휴 이모트

유휴 이모트는 플레이어가 마지막으로 행동한 뒤 지정한 시간이 지나면 자동 재생됩니다. `emotes.json`의 권한 항목에 `idle`을 추가합니다.

```json
{
  "permission": "emote.default",
  "emotes": ["example:wave", "example:hello"],
  "idle": {
    "delay": "300s",
    "emote": ["example:drink"]
  }
}
```

여러 이모트 중 균등하게 선택하려면 ID만 나열합니다.

```json
"emote": ["example:drink", "example:look-around"]
```

가중치를 주려면 ID와 정수를 번갈아 작성하며 합계가 `100`이어야 합니다.

```json
"emote": ["example:drink", 70, "example:look-around", 30]
```

플레이어가 여러 권한을 가지고 있다면 `emotes.json` 위에서부터 확인해 `idle`이 정의된 첫 번째 허용 항목을 사용합니다. 우선순위가 높은 그룹을 먼저 배치하십시오.

```json
"permissions": [
  {
    "permission": "emote.vip",
    "emotes": ["example:vip"],
    "idle": {
      "delay": "120s",
      "emote": ["example:vip-idle"]
    }
  },
  {
    "permission": "emote.default",
    "emotes": ["example:wave"],
    "idle": {
      "delay": "300s",
      "emote": ["example:drink"]
    }
  }
]
```

현재 다른 이모트를 재생 중이면 유휴 이모트는 시작하지 않습니다. 재생 시도가 실패하면 1초 뒤 다시 시도합니다. 여러 후보가 있을 때는 가능한 경우 직전에 재생한 이모트를 연속으로 선택하지 않습니다.
