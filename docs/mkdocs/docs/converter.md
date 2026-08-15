# 웹 변환기

[Emote Converter 열기](https://hanhy06.github.io/emote/converter/){ .md-button .md-button--primary }

웹 변환기는 BD Engine, GeckoLib 및 Animated Java 프로젝트를 Emote Animation JSON으로 변환합니다. 모든 처리는 브라우저 안에서 이루어지며 입력한 프로젝트를 서버로 전송하지 않습니다.

## 기본 작업 순서

1. 지원하는 프로젝트 또는 기존 Emote JSON을 엽니다.
2. 3D 미리보기에서 플레이어 스킨 파츠와 좌표 공간을 지정합니다.
3. 이름, 설명, 재사용 대기시간 및 재생 방식을 설정합니다.
4. 이동, 점프, 공격, 피해 등의 재생 중단 조건을 설정합니다.
5. 필요한 프레임에 Minecraft 명령을 추가합니다.
6. Animation JSON, Sequence ZIP 또는 리소스 팩을 내보냅니다.

## 변환 방식

변환기는 원본 애니메이션의 이징과 보간 곡선을 Minecraft 틱에 맞게 다시 계산합니다. Bézier, Catmull-Rom, bounce 및 elastic 움직임의 주요 지점을 보존하고 위치·회전·크기 오차가 가장 작은 키프레임 배치를 선택합니다.

## 내보내기

- Animation JSON
- 여러 파일을 포함하는 Sequence ZIP
- 새 리소스 팩
- 기존 리소스 팩에 병합한 결과

JSON을 직접 수정해야 한다면 [Animation 포맷](formats/animation.md)과 [Sequence 포맷](formats/sequence.md)을 참고합니다.

!!! warning "신뢰할 수 없는 파일"
    다른 사람이 제공한 프로젝트와 JSON에는 명령이나 리소스가 포함될 수 있습니다. 내용을 확인한 뒤 서버에 설치하십시오.
