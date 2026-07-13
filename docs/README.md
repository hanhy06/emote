# Emote for Fabric

Minecraft Fabric 서버에서 BD Engine humanoid 데이터팩 이모트를 재생하는 모드입니다. 이모트 모델과 플레이어 스킨 합성은 서버에서 처리하며, MineSkin API 키가 없거나 처리가 실패하면 데이터팩에 들어 있는 기본 스킨을 그대로 사용합니다.

## 설치

1. 서버 `mods` 폴더에 모드 jar와 필수 의존성을 넣습니다.
2. 이모트 데이터팩 zip 또는 폴더를 월드의 `datapacks` 폴더에 넣습니다.
3. 서버를 시작하거나 `/emote reload`를 실행합니다.

루트에 유효한 `emote-datapack.json`이 있는 데이터팩은 자동으로 발견되고 활성화됩니다. 별도 등록은 필요하지 않습니다.

## 데이터팩 메타데이터

각 이모트 데이터팩 루트에 `emote-datapack.json`을 둡니다.

```json
{
  "schema_version": 1,
  "name": "Wave",
  "description": "Friendly wave",
  "command_name": "wave",
  "default_animation": "default",
  "hide_player": true
}
```

- namespace는 `data/<namespace>`에서 자동 판별합니다.
- 일반 애니메이션은 `data/<namespace>/function/a/<animation>/play_anim.mcfunction`으로 판별합니다.
- 같은 폴더에 `play_anim_loop.mcfunction`이 있으면 `<animation>_loop` 항목을 자동 등록합니다.
- `hide_player`가 `true`이면 재생 중 실제 플레이어를 숨깁니다.
- `data/<namespace>/function/_/create.mcfunction`이 있어야 이모트 namespace로 인식합니다.

BD Engine export zip은 준비 스크립트로 변환할 수 있습니다.

```powershell
python docs\prepare_emote_datapack.py [--defaults] [--swap-left-right] path\to\project.zip
```

스크립트는 플레이어 스킨용 `emote:*` 마커를 추가하고 위 메타데이터를 생성합니다.

## 설정

### `config/emote/config.json`

- `menu_page_size`: 메뉴 한 페이지의 이모트 수
- `mineskin_api_key`: 서버 측 플레이어 스킨 생성에 사용할 MineSkin API 키. 비어 있으면 API를 호출하지 않고 데이터팩 기본 스킨을 사용합니다.
- `mineskin_poll_interval_seconds`: MineSkin 작업 상태 확인 간격(1~60초, 기본 3초)
- `emote_permission`: 모든 이모트 사용에 필요한 기본 권한

성공한 MineSkin 결과와 처리 중인 작업은 디스크에 저장되므로 같은 스킨을 반복 업로드하지 않습니다.

### `config/emote/packs.json`

설정에 없는 이모트 namespace는 기본적으로 활성화되고 추가 권한이 없습니다. 끄거나 별도 권한을 붙일 때만 override를 추가합니다.

```json
{
  "packs": {
    "wave_pack": {
      "enabled": true,
      "permission": "emote.pack.vip"
    },
    "disabled_pack": {
      "enabled": false,
      "permission": ""
    }
  }
}
```

## 명령어

```mcfunction
/emote menu
/emote play wave
/emote play wave default_loop
/emote stop
/emote reload
```

## 라이선스

Apache License 2.0
