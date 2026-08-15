# 시작하기

## 설치

서버의 Minecraft 및 Fabric Loader 버전에 맞는 Emote 모드 파일을 `mods` 디렉터리에 넣습니다. 모든 핵심 기능은 서버 설치만으로 작동합니다.

클라이언트에도 모드를 설치하면 다음 기능이 추가됩니다.

- `V` 키로 여는 이모트 휠
- 재생 중 자동 3인칭 시점 전환
- 서버별 휠 항목 순서 저장

## 첫 실행

서버를 한 번 실행하면 다음 디렉터리와 기본 파일이 생성됩니다.

```text
config/emote/
├── config.json
├── emotes.json
└── animations/
```

기본 예제 이모트도 `animations/` 아래에 생성됩니다. 변환기에서 내보낸 Animation 또는 Sequence JSON을 이 디렉터리에 추가한 뒤 다음 명령으로 다시 불러옵니다.

```text
/emote reload
```

하위 디렉터리도 함께 탐색합니다. 파일 이름이 아니라 JSON 내부의 `id`가 이모트 ID가 됩니다. 잘못된 파일은 개별적으로 건너뛰며, 같은 ID를 가진 파일은 모두 거부합니다.

## 재생

```text
/emote
/emote play <id>
/emote stop
```

클라이언트 모드를 설치했다면 `V` 키로 휠을 열 수 있습니다. 휠의 순서 설정에서 항목을 추가하거나 제거하고 순서를 변경할 수 있습니다.

## 다음 단계

- 권한과 유휴 이모트 구성: [서버 관리](server-management.md)
- 애니메이션 변환: [웹 변환기](converter.md)
- JSON 직접 작성: [Animation 포맷](formats/animation.md), [Sequence 포맷](formats/sequence.md)
