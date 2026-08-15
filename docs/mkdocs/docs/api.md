# Mod API

다른 Fabric 모드에서 `EmoteApi.getInstance()`를 통해 Emote의 등록, 재생과 이벤트 기능을 사용할 수 있습니다.

## Gradle 의존성

Emote는 Modrinth Maven에서 받을 수 있습니다. 저장소에 Modrinth Maven을 추가합니다.

```groovy title="build.gradle"
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = "https://api.modrinth.com/maven"
            }
        }
        filter {
            includeGroup "maven.modrinth"
        }
    }
}
```

의존성의 `[VERSION_ID]`를 사용할 Emote 파일의 Modrinth 버전 ID로 바꿉니다. 버전 이름이 아니라 Modrinth가 각 버전에 부여한 ID를 사용합니다.

```groovy title="build.gradle"
dependencies {
    implementation "maven.modrinth:qUF0jygw:[VERSION_ID]"
}
```

[Emote 버전 목록](https://modrinth.com/mod/emote/versions)에서 대상 Minecraft 버전에 맞는 파일과 버전 ID를 확인할 수 있습니다.

`fabric.mod.json`에도 Emote 의존성을 선언합니다.

```json title="fabric.mod.json"
{
  "depends": {
    "emote": "*"
  }
}
```

## API 범위

현재 API가 제공하는 주요 기능은 다음과 같습니다.

- 런타임 이모트 등록 및 해제
- 플레이어 이모트 재생과 중지
- 현재 재생 상태 조회
- 취소 가능한 재생 요청 리스너
- 재생 시작·종료 생명주기 리스너

상태를 변경하는 API 호출은 Minecraft 서버 스레드에서 실행해야 합니다.

패키지별 타입과 메서드는 저장소의 `io.github.hanhy06.emote.api` 패키지에서 확인할 수 있습니다. 실제 등록과 이벤트 예제는 API가 확정된 뒤 이 문서에 추가합니다.
