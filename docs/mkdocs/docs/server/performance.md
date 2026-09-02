# Performance

## 테스트 환경

| 항목           | 환경                         |
|----------------|------------------------------|
| CPU            | Intel Core Ultra 5 250K Plus |
| RAM            | 24 GB (싱글 채널)            |
| 운영체제       | Windows 11                   |
| Minecraft 버전 | 26.2                         |
| 월드           | 일반 야생 월드               |

<div id="stress-test-chart" class="stress-chart" data-selected-metric="emote">
  <div class="stress-chart__controls" role="group" aria-label="그래프 지표 선택">
    <button type="button" data-metric="mspt" aria-pressed="false">MSPT</button>
    <button type="button" data-metric="emote" aria-pressed="true">에모트 처리</button>
    <button type="button" data-metric="packets" aria-pressed="false">패킷 처리량</button>
    <button type="button" data-metric="encode" aria-pressed="false">패킷 처리 시간</button>
  </div>
  <div class="stress-chart__canvas">
    <canvas id="stressTestChart" aria-label="동시 에모트 사용자 증가에 따른 스트레스 테스트 결과"></canvas>
  </div>
  <p class="stress-chart__summary" aria-live="polite"></p>
  <noscript>그래프를 보려면 JavaScript를 활성화해야 한다. 전체 측정값은 아래 상세 결과에서 확인할 수 있다.</noscript>
</div>
