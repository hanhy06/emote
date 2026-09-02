(() => {
  const chartLibraryUrl = new URL("vendor/chart.umd.min.js", document.currentScript.src).href;
  const measurements = [
    { players: 10, displays: 220 },
    { players: 25, displays: 440 },
    { players: 50, displays: 890 },
    { players: 75, displays: 1340 },
    { players: 100, displays: 1760 }
  ];
  const labels = measurements.map(({ players, displays }) => [`${players}명`, `약 ${displays.toLocaleString("ko-KR")} 디스플레이`]);
  const metrics = {
    mspt: {
      title: "서버 MSPT",
      unit: "ms",
      summary: "동시 진행 인원이 늘수록 MSPT가 전반적으로 증가했다. 75명에서는 평균이 50 ms를 넘었고, 100명 측정에서는 p95가 88.11 ms까지 상승했다.",
      datasets: [
        { label: "평균", data: [9.66, 13.33, 26.93, 50.66, 31.21], borderColor: "#29b6f6", backgroundColor: "#29b6f6" },
        { label: "p95", data: [16.78, 21.59, 33.60, 56.37, 88.11], borderColor: "#ab80ff", backgroundColor: "#ab80ff" },
        { label: "최대", data: [23.46, 30.38, 40.12, 66.20, 98.04], borderColor: "#ffb74d", backgroundColor: "#ffb74d" }
      ]
    },
    emote: {
      title: "에모트 Tick 처리 시간",
      unit: "ms",
      summary: "에모트 처리 시간은 100명에서도 평균 4.109 ms, 최대 8.931 ms로 측정되어 20 TPS의 50 ms 틱 예산 안에 머물렀다.",
      datasets: [
        { label: "평균", data: [0.710, 1.030, 1.982, 3.006, 4.109], borderColor: "#29b6f6", backgroundColor: "#29b6f6" },
        { label: "최대", data: [7.872, 8.403, 7.196, 6.333, 8.931], borderColor: "#ffb74d", backgroundColor: "#ffb74d" },
        { label: "20 TPS 틱 예산", data: [50, 50, 50, 50, 50], borderColor: "#ef6c6c", backgroundColor: "#ef6c6c", borderDash: [7, 6], pointRadius: 0, pointHoverRadius: 0 }
      ]
    },
    packets: {
      title: "런타임 패킷 처리량",
      unit: "패킷/초",
      summary: "런타임 패킷 처리량은 75명까지 빠르게 증가했으며, 100명에서는 초당 약 153만 패킷으로 측정됐다.",
      datasets: [
        { label: "런타임", data: [33912, 159004, 650309, 1414816, 1534801], borderColor: "#66d17a", backgroundColor: "#66d17a" }
      ]
    },
    encode: {
      title: "틱당 패킷 처리 시간",
      unit: "ms",
      summary: "틱당 패킷 처리 시간은 인원수와 함께 증가해 100명에서 평균 60.895 ms, 최대 89.756 ms로 측정됐다.",
      datasets: [
        { label: "평균", data: [1.740, 5.749, 18.473, 40.456, 60.895], borderColor: "#ab80ff", backgroundColor: "#ab80ff" },
        { label: "최대", data: [6.142, 16.079, 30.780, 50.546, 89.756], borderColor: "#ffb74d", backgroundColor: "#ffb74d" }
      ]
    }
  };

  let chartLibraryPromise = null;
  let stressTestChart = null;

  function loadChartLibrary() {
    if (window.Chart) {
      return Promise.resolve();
    }
    if (chartLibraryPromise) {
      return chartLibraryPromise;
    }

    chartLibraryPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = chartLibraryUrl;
      script.onload = resolve;
      script.onerror = reject;
      document.head.appendChild(script);
    });
    return chartLibraryPromise;
  }

  function renderChart() {
    const root = document.getElementById("stress-test-chart");
    const canvas = document.getElementById("stressTestChart");
    if (!root || !canvas) {
      return;
    }

    const selectedMetric = root.dataset.selectedMetric || "emote";
    const metric = metrics[selectedMetric];
    root.querySelector(".stress-chart__summary").textContent = metric.summary;
    root.querySelectorAll("[data-metric]").forEach(button => {
      button.setAttribute("aria-pressed", String(button.dataset.metric === selectedMetric));
    });

    loadChartLibrary().then(() => {
      if (stressTestChart) {
        stressTestChart.destroy();
      }

      stressTestChart = new Chart(canvas.getContext("2d"), {
        type: "line",
        data: {
          labels,
          datasets: metric.datasets.map(dataset => ({
            ...dataset,
            tension: 0.15,
            borderWidth: 3,
            pointRadius: dataset.pointRadius ?? 4,
            pointHoverRadius: dataset.pointHoverRadius ?? 6
          }))
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          interaction: {
            mode: "index",
            intersect: false
          },
          plugins: {
            title: {
              display: true,
              text: metric.title
            },
            legend: {
              labels: {
                usePointStyle: true
              }
            },
            tooltip: {
              itemSort: (a, b) => b.parsed.y - a.parsed.y,
              callbacks: {
                title: context => {
                  const measurement = measurements[context[0].dataIndex];
                  return `${measurement.players}명 동시 진행 · 약 ${measurement.displays.toLocaleString("ko-KR")} 디스플레이`;
                },
                label: context => `${context.dataset.label}: ${context.parsed.y.toLocaleString("ko-KR")} ${metric.unit}`
              }
            }
          },
          scales: {
            x: {
              title: {
                display: true,
                text: "동시 진행 플레이어 수 / 디스플레이 수"
              }
            },
            y: {
              beginAtZero: true,
              title: {
                display: true,
                text: metric.unit
              },
              ticks: {
                callback: value => metric.unit === "패킷/초" ? Number(value).toLocaleString("ko-KR") : value
              }
            }
          }
        }
      });
    }).catch(() => {
      root.querySelector(".stress-chart__canvas").textContent = "그래프 라이브러리를 불러오지 못했다. 전체 결과는 아래 표에서 확인할 수 있다.";
    });
  }

  function initializeChart() {
    const root = document.getElementById("stress-test-chart");
    if (!root) {
      return;
    }

    if (root.dataset.initialized !== "true") {
      root.dataset.initialized = "true";
      root.querySelectorAll("[data-metric]").forEach(button => {
        button.addEventListener("click", () => {
          root.dataset.selectedMetric = button.dataset.metric;
          renderChart();
        });
      });
    }
    renderChart();
  }

  if (window.document$) {
    window.document$.subscribe(initializeChart);
  } else {
    document.addEventListener("DOMContentLoaded", initializeChart);
  }
})();
