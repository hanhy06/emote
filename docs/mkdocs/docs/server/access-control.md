# 권한과 접근 제어

Emote의 접근 정책은 `config/emote/emotes.json`에서 정하고, 실제 권한 부여는 LuckPerms 같은 Fabric Permissions API 호환 권한 모드에서 수행합니다.

## 권한 종류

| 권한             | 용도                                                                             | 기본값                                              |
|------------------|----------------------------------------------------------------------------------|-----------------------------------------------------|
| `emote.manage`   | 목록, 다시 불러오기, 활성화·비활성화, 타인 중지, 전체 중지, 스트레스 테스트 명령 | 게임 마스터 등급 운영자에게 허용                    |
| `emote.bypass`   | 서버 정책을 무시하고 실행.                                                       | 누구에게도 자동 부여하지 않음                       |
| `emote.default`  | `emotes.json`의 기본 지급 그룹                                                   | 별도 권한 모드 설정이 없으면 모든 플레이어에게 허용 |
| 사용자 정의 권한 | VIP, 후원자, 관리자 등 서버별 이모트 그룹                                        | 권한 모드에서 직접 부여                             |

LuckPerms 예시는 다음과 같습니다.

```text
/lp group admin permission set emote.manage true
/lp group vip permission set emote.vip true
/lp user <player> permission set emote.bypass true
```

`emote.manage`는 관리 명령만 허용합니다. 이 권한만 받은 운영자가 모든 이모트를 자동으로 사용할 수 있는 것은 아닙니다. 모든 이모트 사용 권한도 주려면 별도 그룹에 `"emotes": ["*"]`를 설정하거나 `emote.bypass`를 부여해야 합니다.

자세한 사용법은 [LuckPerms Wiki](https://luckperms.net/wiki/Home)에서 확인합니다.

## `emotes.json`

```json
{
  "schema_version": 2,
  "disabled": ["example:broken"],
  "permissions": [
    {
      "permission": "emote.default",
      "emotes": ["example:wave", "example:hello"]
    },
    {
      "permission": "emote.vip",
      "emotes": ["example:dance", "example:sit"]
    },
    {
      "permission": "emote.admin",
      "emotes": ["*"]
    }
  ]
}
```

플레이어가 이모트를 사용할 수 있는지는 다음 순서로 판단합니다.

1. `emote.bypass`가 있으면 허용합니다.
2. ID가 `disabled`에 있으면 거부합니다.
3. 플레이어가 가진 권한 그룹 중 `emotes`에 해당 ID 또는 `*`가 있으면 허용합니다.
4. 어느 규칙에도 해당하지 않으면 거부합니다.

허용되지 않은 이모트는 메뉴, 검색, 명령 제안과 클라이언트 휠 목록에 나타나지 않으며 ID를 직접 입력해도 재생되지 않습니다.

!!! note "`emote.default`도 일반 권한입니다"
    `emote.default`는 기본값이 허용일 뿐입니다. 권한 모드에서 플레이어에게 `emote.default=false`를 명시하면 기본 목록도 사용할 수 없습니다.

## `disabled`의 동작

`disabled`는 파일을 삭제하거나 로드에서 제외하는 목록이 아닙니다. 이모트 정의는 계속 로드되지만 일반 플레이어의 선택과 직접 재생을 차단합니다.

```text
/emote disable example:dance
```

이 명령은 다음을 수행합니다.

1. `example:dance`를 `emotes.json`의 `disabled`에 저장합니다.
2. 해당 ID의 현재 재생을 중지합니다.
3. 전체 Emote 구성을 다시 불러옵니다. 이 과정에서 다른 재생 중인 이모트도 중지됩니다.
4. 플레이어 메뉴와 휠 목록을 갱신합니다.

다시 허용하려면 다음 명령을 사용합니다.

```text
/emote enable example:dance
```

`emote.bypass`를 가진 플레이어에게는 비활성화된 이모트도 목록에 보이고 재생할 수 있습니다.

비활성화된 Animation을 다른 Sequence가 내부 단계로 참조하는 것은 막지 않습니다. Sequence 전체를 막으려면 Sequence 자신의 ID를 `disabled`에 추가해야 합니다.

## `emote.bypass`가 무시하지 않는 것

`emote.bypass`는 관리자 권한의 상위 개념이 아닙니다. 다음 제한은 그대로 적용됩니다.

- 존재하지 않는 ID는 재생할 수 없습니다.
- `standalone: false` Animation은 직접 재생할 수 없습니다.
- 잘못되어 로드되지 않은 Animation 또는 Sequence는 사용할 수 없습니다.
- 디스플레이 엔티티 상한과 재생 시작 실패를 무시하지 않습니다.
- `emote.manage`가 필요한 관리 명령을 허용하지 않습니다.
