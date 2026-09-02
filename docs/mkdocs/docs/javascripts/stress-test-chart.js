(() => {
  const chartLibraryUrl = new URL("vendor/chart.umd.min.js", document.currentScript.src).href;
  const measurements = [
    { players: 10, displays: 220 },
    { players: 25, displays: 440 },
    { players: 50, displays: 890 },
    { players: 75, displays: 1340 },
    { players: 100, displays: 1760 }
  ];
  const labels = measurements.map(({ players, displays }) => [`${players}명 상당`, `약 ${displays.toLocaleString("ko-KR")} 디스플레이`]);
  const metrics = {
    mspt: {
      title: "서버 MSPT",
      unit: "ms",
      summary: "합성 부하가 커질수록 MSPT가 증가했다. 75명 상당에서는 평균이 50 ms를 넘었고, 100명 상당에서는 평균 81.21 ms와 p95 88.11 ms가 측정됐다.",
      datasets: [
        { label: "평균", data: [9.66, 13.33, 26.93, 50.66, 81.21], borderColor: "#29b6f6", backgroundColor: "#29b6f6" },
        { label: "p95", data: [16.78, 21.59, 33.60, 56.37, 88.11], borderColor: "#ab80ff", backgroundColor: "#ab80ff" },
        { label: "최대", data: [23.46, 30.38, 40.12, 66.20, 98.04], borderColor: "#ffb74d", backgroundColor: "#ffb74d" },
        { label: "20 TPS 기준", data: [50, 50, 50, 50, 50], borderColor: "#ef5350", backgroundColor: "#ef5350", borderDash: [7, 6], pointRadius: 0, pointHoverRadius: 0 }
      ]
    },
    duration: {
      type: "bar",
      title: "실제 시간 구성",
      unit: "초",
      axisTitle: "시간 (초)",
      stacked: true,
      summary: "600틱의 실제 경과 시간과 구성을 표시한다. 100명 상당에서는 총 51.4초 중 패킷 변경 감지와 인코딩에 해당하는 Network가 41.3초를 차지했다.",
      datasets: [
        { label: "Setup", data: [0.2, 0.2, 0.7, 1.5, 2.5], backgroundColor: "#fbc02d", borderColor: "#fdd835" },
        { label: "Emote", data: [0.4, 0.6, 1.1, 1.8, 2.5], backgroundColor: "#43a047", borderColor: "#66bb6a" },
        { label: "Network", data: [1.0, 3.4, 11.1, 24.3, 41.3], backgroundColor: "#00acc1", borderColor: "#26c6da" },
        { label: "Server/idle", data: [28.4, 25.8, 17.1, 4.3, 5.0], backgroundColor: "#78909c", borderColor: "#90a4ae" },
        { label: "Cleanup", data: [0.0, 0.0, 0.0, 0.1, 0.1], backgroundColor: "#fb8c00", borderColor: "#ffa726" }
      ]
    }
  };
  const hoverLinePlugin = {
    id: "hoverLine",
    afterDatasetsDraw(chart) {
      const activeElements = chart.tooltip?.getActiveElements();
      if (!activeElements?.length) {
        return;
      }

      const { ctx, chartArea } = chart;
      const x = activeElements[0].element.x;
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(x, chartArea.top);
      ctx.lineTo(x, chartArea.bottom);
      ctx.lineWidth = 1;
      ctx.strokeStyle = "rgba(255, 255, 255, 0.75)";
      ctx.stroke();
      ctx.restore();
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

    const selectedMetric = root.dataset.selectedMetric || "mspt";
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
        type: metric.type || "line",
        plugins: [hoverLinePlugin],
        data: {
          labels,
          datasets: metric.datasets.map(dataset => ({
            ...dataset,
            tension: 0.15,
            borderWidth: dataset.borderWidth ?? (metric.type === "bar" ? 1 : 3),
            borderRadius: metric.type === "bar" ? 2 : undefined,
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
              itemSort: metric.type === "bar" ? (a, b) => a.datasetIndex - b.datasetIndex : (a, b) => b.parsed.y - a.parsed.y,
              callbacks: {
                title: context => {
                  const measurement = measurements[context[0].dataIndex];
                  return `${measurement.players}명 상당 · 약 ${measurement.displays.toLocaleString("ko-KR")} 디스플레이`;
                },
                label: context => `${context.dataset.label}: ${context.parsed.y.toLocaleString("ko-KR")} ${context.dataset.unit || metric.unit}`,
                footer: context => metric.stacked
                  ? `합계: ${context.reduce((total, item) => total + item.parsed.y, 0).toFixed(1)} ${metric.unit}`
                  : undefined
              }
            }
          },
          scales: {
            x: {
              stacked: metric.stacked || false,
              title: {
                display: true,
                text: "동시 진행 상당 부하 / 디스플레이 수"
              }
            },
            y: {
              beginAtZero: true,
              stacked: metric.stacked || false,
              title: {
                display: true,
                text: metric.axisTitle || "MSPT (ms)"
              },
              ticks: {
                callback: value => value
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
