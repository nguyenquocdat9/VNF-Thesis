"""
generate_chart.py
=================
Reads 12 simulation logs (4 levels x 3 algos) -> writes results_chart.html.

Log file naming: simulation_log_{algo}_{level}.txt
  e.g. simulation_log_mshor_L3.txt

Charts:
  1. Grouped bar  -- M2 score per level (X=L1..L4, groups=3 algos)  [MAIN]
  2. Line trend   -- M2 across levels per algo (scaling behavior)
  3. Grouped bar  -- MIPS used vs SFC saved (M3) for L3
  4. Line+Scatter -- SFC violations resolved vs MIPS budget for L3

Run:
    python generate_chart.py
Output:
    results_chart.html
"""

import re, json, os, sys
from datetime import datetime

LOG_DIR      = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
WORKLOAD_DIR = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc"
OUTPUT       = os.path.join(LOG_DIR, "results_chart.html")

ALGOS  = ["MSH-OR", "WorstFirst", "QueueFirst"]
LEVELS = ["L1", "L2", "L3", "L4"]
LEVEL_LABELS = {
    "L1": ["L1", "(3/3/3)"],
    "L2": ["L2", "(3/5/8)"],
    "L3": ["L3", "(3/8/12)"],
    "L4": ["L4", "(3/12/18)"],
}
LEVEL_DESCS = {
    "L1": "3/3/3 req/SFC",
    "L2": "3/5/8 req/SFC",
    "L3": "3/8/12 req/SFC",
    "L4": "3/12/18 req/SFC",
}

LOG_KEY = {
    "MSH-OR":     "mshor",
    "WorstFirst": "worstfirst",
    "QueueFirst": "queuefirst",
}

PALETTE = {
    "MSH-OR":     {"line": "#27ae60", "fill": "rgba(46,204,113,0.15)",  "bar": "rgba(46,204,113,0.82)"},
    "WorstFirst": {"line": "#2980b9", "fill": "rgba(52,152,219,0.10)",  "bar": "rgba(52,152,219,0.82)"},
    "QueueFirst": {"line": "#c0392b", "fill": "rgba(231,76,60,0.08)",   "bar": "rgba(231,76,60,0.82)"},
}
EFF_COLOR = {
    "MSH-OR": "#27ae60", "WorstFirst": "#e67e22", "QueueFirst": "#c0392b",
}
MIPS_TICKS = [0, 100, 200, 300, 400, 500, 600, 700]
REF_LEVEL  = "L3"

# ── parsers ──────────────────────────────────────────────────────────────────

def read_log(path):
    for enc in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            with open(path, encoding=enc, errors="replace") as f:
                return f.readlines()
        except FileNotFoundError:
            return []
    return []


RE_VERT_SCALE = re.compile(
    r"VERTICAL SCALE \| [0-9.]+ -> [0-9.]+ MIPS \(delta=([0-9.]+)\)"
)


def parse_scale_events(lines):
    vs_at = {}
    for i, line in enumerate(lines):
        m = RE_VERT_SCALE.search(line)
        if m:
            vs_at[i] = float(m.group(1))

    vs_indices = sorted(vs_at)
    events = []

    for i, line in enumerate(lines):
        if "[AFTER]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            continue

        vnf_m = re.search(r"\[AFTER\]\s+(\S+)", line)
        dc2_m = re.search(r"DC2=\+?([0-9]+\.?[0-9]*)", line)
        dc3_m = re.search(r"DC3=\+?([0-9]+\.?[0-9]*)", line)
        dm_m  = re.search(r"dMips=([0-9]+\.?[0-9]*)", line)
        if not (vnf_m and dc2_m and dc3_m and dm_m):
            continue

        dmips = float(dm_m.group(1))
        paired_delta = None
        for vi in reversed(vs_indices):
            if vi < i and abs(vs_at[vi] - dmips) < 0.01:
                paired_delta = vs_at[vi]
                break

        delta = paired_delta if paired_delta is not None else dmips
        events.append({
            "time":       t,
            "vnf":        vnf_m.group(1),
            "delta_mips": delta,
            "dc2":        float(dc2_m.group(1)),
            "dc3":        float(dc3_m.group(1)),
        })

    return sorted(events, key=lambda e: e["time"])


def parse_cis(lines):
    for line in reversed(lines):
        if "[CIS]" not in line or "M2_sum" not in line:
            continue
        m1_m = re.search(r"M1_sum=([0-9]+\.?[0-9]*)", line)
        m2_m = re.search(r"M2_sum=([0-9]+\.?[0-9]*)", line)
        m3_m = re.search(r"M3_sum=([0-9]+\.?[0-9]*)", line)
        if m2_m and m3_m:
            return (
                float(m1_m.group(1)) if m1_m else 0.0,
                float(m2_m.group(1)),
                float(m3_m.group(1)),
            )
    return 0.0, 0.0, 0.0


def raw_curve_points(events, extend_to=700.0):
    pts = [(0.0, 0.0)]
    cum_m, cum_s = 0.0, 0.0
    for e in events:
        cum_m += e["delta_mips"]
        cum_s += e["dc3"]
        pts.append((round(cum_m, 2), round(cum_s, 2)))
    if pts[-1][0] < extend_to:
        pts.append((extend_to, pts[-1][1]))
    return pts


def interp_at_ticks(events, mips_ticks):
    pts = raw_curve_points(events, extend_to=mips_ticks[-1])
    result = []
    for tick in mips_ticks:
        if tick <= 0.0:
            result.append(0.0)
            continue
        if tick >= pts[-1][0]:
            result.append(round(pts[-1][1], 2))
            continue
        for k in range(len(pts) - 1):
            x0, y0 = pts[k]
            x1, y1 = pts[k + 1]
            if x0 <= tick <= x1:
                span = x1 - x0
                if span < 1e-9:
                    result.append(round(y1, 2))
                else:
                    result.append(round(y0 + (tick - x0) / span * (y1 - y0), 2))
                break
    return result

# ── data collection ──────────────────────────────────────────────────────────

def collect_all(log_dir):
    """Returns data[algo][level] = {m1, m2, m3, events, total_mips, efficiency}"""
    data = {algo: {} for algo in ALGOS}
    missing = []

    for algo in ALGOS:
        key = LOG_KEY[algo]
        for level in LEVELS:
            fname = f"simulation_log_{key}_{level}.txt"
            path  = os.path.join(log_dir, fname)
            lines = read_log(path)
            if not lines:
                print(f"  [WARN] not found: {fname}", file=sys.stderr)
                missing.append(fname)
                data[algo][level] = None
                continue

            events     = parse_scale_events(lines)
            m1, m2, m3 = parse_cis(lines)
            total_mips = round(sum(e["delta_mips"] for e in events), 1)
            efficiency = round(m3 / total_mips, 3) if total_mips > 0 else 0.0

            data[algo][level] = {
                "events":     events,
                "m1":         m1,
                "m2":         m2,
                "m3":         m3,
                "total_mips": total_mips,
                "efficiency": efficiency,
            }

    if missing:
        print(f"\n  [WARN] {len(missing)} log file(s) missing -- those cells will show 0.",
              file=sys.stderr)

    return data

# ── build JS data payload ─────────────────────────────────────────────────────

def safe(d, key, default=0.0):
    return d[key] if d is not None else default


def build_payload(data):

    # ── Chart 1: Grouped bar M2 per level x algo (MAIN) ─────────────────────
    c1_datasets = []
    for algo in ALGOS:
        c1_datasets.append({
            "label":           algo,
            "data":            [safe(data[algo][lv], "m2") for lv in LEVELS],
            "backgroundColor": PALETTE[algo]["bar"],
            "borderColor":     PALETTE[algo]["line"],
            "borderWidth":     2,
            "borderRadius":    5,
        })

    # Best algo per level
    c1_best = []
    for lv in LEVELS:
        best_algo = max(ALGOS, key=lambda a: safe(data[a][lv], "m2"))
        best_val  = safe(data[best_algo][lv], "m2")
        c1_best.append({"algo": best_algo, "val": best_val})

    c1 = {
        "level_labels": [LEVEL_LABELS[lv] for lv in LEVELS],
        "datasets":     c1_datasets,
        "best":         c1_best,
        "level_descs":  [LEVEL_DESCS[lv] for lv in LEVELS],
    }

    # ── Chart 2: Line M2 trend per algo across levels ────────────────────────
    c2_series = []
    for algo in ALGOS:
        c2_series.append({
            "label":      algo,
            "data":       [safe(data[algo][lv], "m2") for lv in LEVELS],
            "line_color": PALETTE[algo]["line"],
            "fill_color": PALETTE[algo]["fill"],
        })

    # Compute % gain MSH-OR vs QueueFirst at each level
    gains = []
    for lv in LEVELS:
        m_val = safe(data["MSH-OR"][lv], "m2")
        q_val = safe(data["QueueFirst"][lv], "m2")
        gains.append(round((m_val - q_val) / q_val * 100, 1) if q_val > 0 else 0.0)

    c2 = {
        "level_labels": [LEVEL_DESCS[lv] for lv in LEVELS],
        "series":       c2_series,
        "mshor_gains":  gains,
    }

    # ── Charts 3/4: Single-level detail for REF_LEVEL ───────────────────────
    ref = REF_LEVEL
    ref_data = {algo: data[algo][ref] for algo in ALGOS}

    # Chart 3: grouped bar MIPS vs M3
    max_mips   = max(safe(ref_data[a], "total_mips") for a in ALGOS)
    eff_offset = round(max_mips * 0.04, 1)
    c3 = {
        "level":  ref,
        "labels": ALGOS,
        "mips":   [safe(ref_data[a], "total_mips") for a in ALGOS],
        "m3":     [safe(ref_data[a], "m3")         for a in ALGOS],
        "y_max":  round(max_mips * 1.12),
        "eff_annots": {
            a: {
                "yValue":  round(safe(ref_data[a], "total_mips") + eff_offset, 1),
                "content": f"{safe(ref_data[a], 'efficiency'):.3f} SFC/MIPS",
                "color":   EFF_COLOR[a],
            }
            for a in ALGOS
        },
    }
    best_eff  = max(ALGOS, key=lambda a: safe(ref_data[a], "efficiency"))
    worst_eff = min(ALGOS, key=lambda a: safe(ref_data[a], "efficiency"))
    eff_best  = safe(ref_data[best_eff], "efficiency")
    eff_worst = safe(ref_data[worst_eff], "efficiency")
    eff_ratio = round(eff_best / eff_worst, 1) if eff_worst > 0 else 0.0
    c3["subtitle"] = f"{best_eff} efficiency = {eff_ratio}× {worst_eff}"
    c3["legend"]   = (f"<b>{best_eff}: {safe(ref_data[best_eff], 'total_mips')} MIPS → "
                      f"{int(safe(ref_data[best_eff], 'm3'))} SFC saved "
                      f"({eff_best:.3f} SFC/MIPS) — {eff_ratio}× more efficient than {worst_eff}</b>")

    # Chart 4: SFC violations resolved vs cumulative MIPS budget
    c4_series = []
    for algo in ALGOS:
        events    = ref_data[algo]["events"] if ref_data[algo] else []
        tick_vals = interp_at_ticks(events, MIPS_TICKS)
        c4_series.append({
            "label":      algo,
            "tick_vals":  tick_vals,
            "line_color": PALETTE[algo]["line"],
            "fill_color": PALETTE[algo]["fill"],
        })
    c4 = {"mips_ticks": MIPS_TICKS, "series": c4_series, "level": ref}

    return {
        "generated":  datetime.now().strftime("%Y-%m-%d %H:%M"),
        "ref_level":  ref,
        "level_list": LEVELS,
        "c1": c1,
        "c2": c2,
        "c3": c3,
        "c4": c4,
    }

# ── HTML template ─────────────────────────────────────────────────────────────

HTML_TEMPLATE = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>MSH-OR vs Baselines &#8212; Multi-Level Results</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation@3.0.1/dist/chartjs-plugin-annotation.min.js"></script>
<style>
  body       { font-family: Arial, sans-serif; background: #f4f6f9; margin: 0; padding: 24px; }
  h1         { text-align: center; color: #2c3e50; margin-bottom: 4px; }
  .subtitle  { text-align: center; color: #7f8c8d; font-size: 13px; margin-bottom: 32px; }
  .section-title { font-size: 14px; color: #7f8c8d; text-align: center;
                   margin: 32px 0 16px; letter-spacing: 1px; text-transform: uppercase; }
  .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 28px;
                 max-width: 1200px; margin: 0 auto; }
  .chart-card  { background: #fff; border-radius: 10px;
                 box-shadow: 0 2px 8px rgba(0,0,0,0.1); padding: 24px; }
  .chart-card.wide { grid-column: 1 / -1; }
  .chart-card h2 { margin: 0 0 6px 0; font-size: 15px; color: #34495e; }
  .chart-card p  { margin: 0 0 16px 0; font-size: 12px; color: #95a5a6; }
  canvas     { max-height: 340px; }
  .legend-box { margin-top: 14px; font-size: 11px; color: #7f8c8d;
                border-top: 1px solid #eee; padding-top: 10px; }
  .gen-note  { text-align: center; font-size: 11px; color: #bdc3c7; margin-top: 24px; }
  .badge     { display: inline-block; padding: 2px 8px; border-radius: 10px;
               font-size: 11px; font-weight: bold; }
</style>
</head>
<body>

<h1>MSH-OR vs Baselines &#8212; NFV Auto-Scaling (Multi-Level)</h1>
<p class="subtitle">
  Fat-Tree k=6 &#124; 54 hosts &#124; 6 SFC &#124; 5 VNF &#124;
  4 workload levels (L1..L4) &#124; 3 algorithms &#215; 4 levels = 12 simulations &#124;
  CloudSimSDN-NFV v2.0
</p>

<!-- ====== SECTION 1: Multi-Level Comparison ====== -->
<p class="section-title">&#9658; Cross-Level Comparison</p>

<div class="charts-grid">

  <div class="chart-card wide">
    <h2>Chart 1 &#8212; M2 Score per Workload Level (Key Metric)</h2>
    <p>
      sum(DC2): Priority-Weighted SLA Breach Reduction across all 4 workload intensities.
      Higher = better. X groups = workload levels (req/SFC per phase), bars = algorithms.
    </p>
    <canvas id="c1"></canvas>
    <div class="legend-box" id="legend1"></div>
  </div>

  <div class="chart-card wide">
    <h2>Chart 2 &#8212; M2 Trend Across Workload Levels</h2>
    <p>
      How M2 score scales as workload intensity increases (L1 &#8594; L4).
      Steeper slope = algorithm benefits more from higher load conditions.
    </p>
    <canvas id="c2"></canvas>
    <div class="legend-box" id="legend2"></div>
  </div>

</div>

<!-- ====== SECTION 2: Reference Level Detail (L3) ====== -->
<p class="section-title" id="refTitle">&#9658; Reference Level Detail</p>

<div class="charts-grid">

  <div class="chart-card wide">
    <h2 id="c3title">Chart 3 &#8212; MIPS Used vs SFC Violations Resolved</h2>
    <p>Resource efficiency: total MIPS allocated vs total SFC violations eliminated (M3).</p>
    <canvas id="c3"></canvas>
    <div class="legend-box" id="legend3"></div>
  </div>

  <div class="chart-card wide">
    <h2 id="c4title">Chart 4 &#8212; SFC Violations Resolved vs Cumulative MIPS Budget</h2>
    <p>
      If each algorithm were allowed only X total MIPS, how many SFC violations could it resolve?
      Linearly interpolated from actual scale events in the simulation log.
    </p>
    <canvas id="c4"></canvas>
    <div class="legend-box" id="legend4"></div>
  </div>

</div>

<p class="gen-note" id="genNote"></p>

<script>
// Auto-generated by generate_chart.py -- do not edit manually.
const DATA = __CHART_DATA__;

document.getElementById('genNote').textContent = 'Generated: ' + DATA.generated;
const ref = DATA.ref_level;
document.getElementById('refTitle').textContent = '\\u25BA Reference Level Detail (' + ref + ')';
document.getElementById('c3title').textContent  = 'Chart 3 — MIPS Used vs SFC Violations Resolved (' + ref + ')';
document.getElementById('c4title').textContent  = 'Chart 4 — SFC Violations vs MIPS Budget (' + ref + ')';

// ============================================================
// Chart 1: Grouped bar -- M2 per level, grouped by algo
// ============================================================
(function () {
  const d = DATA.c1;
  new Chart(document.getElementById('c1'), {
    type: 'bar',
    data: {
      labels:   d.level_labels,
      datasets: d.datasets,
    },
    options: {
      responsive: true,
      plugins: {
        legend: { position: 'top' },
        tooltip: {
          mode: 'index',
          callbacks: {
            label: function (ctx) {
              return ctx.dataset.label + ': M2=' + ctx.parsed.y.toFixed(2);
            }
          }
        },
      },
      scales: {
        x: {
          title: { display: true, text: 'Workload Level (req/SFC per phase)' },
          grid:  { display: false },
        },
        y: {
          beginAtZero: true,
          title: { display: true, text: 'M2 = Σ DC2 (priority-weighted SLA reduction)' },
        }
      }
    }
  });

  // Legend: best per level
  const rows = d.best.map(function (b, i) {
    const c = d.datasets.find(function (ds) { return ds.label === b.algo; });
    const col = c ? c.borderColor : '#555';
    return '<b style="color:' + col + '">' + DATA.level_list[i] + ': ' +
           b.algo + ' = ' + b.val.toFixed(2) + '</b>';
  });
  document.getElementById('legend1').innerHTML = rows.join(' &nbsp;|&nbsp; ');
}());

// ============================================================
// Chart 2: Line M2 trend across levels
// ============================================================
(function () {
  const d = DATA.c2;
  const datasets = d.series.map(function (s) {
    return {
      label:           s.label,
      data:            s.data,
      borderColor:     s.line_color,
      backgroundColor: s.fill_color,
      fill:            false,
      tension:         0.3,
      pointRadius:     7,
      pointHoverRadius: 9,
      borderWidth:     2.5,
    };
  });

  new Chart(document.getElementById('c2'), {
    type: 'line',
    data: { labels: d.level_labels, datasets: datasets },
    options: {
      responsive: true,
      plugins: {
        legend: { position: 'top' },
        tooltip: {
          callbacks: {
            label: function (ctx) {
              return ctx.dataset.label + ': M2=' + ctx.parsed.y.toFixed(2);
            }
          }
        },
      },
      scales: {
        x: {
          title: { display: true, text: 'Workload Level (req/SFC per phase)' },
          grid:  { display: false },
        },
        y: {
          beginAtZero: true,
          title: { display: true, text: 'M2 Score' },
        }
      }
    }
  });

  // Legend: MSH-OR gain vs QueueFirst at each level
  const mshor_s = d.series.find(function (s) { return s.label === 'MSH-OR'; });
  const gains   = d.mshor_gains;
  const gainStr = DATA.level_list.map(function (lv, i) {
    return lv + ': +' + gains[i] + '%';
  }).join(' | ');
  document.getElementById('legend2').innerHTML =
    '<b style="color:#27ae60">MSH-OR advantage over QueueFirst: ' + gainStr + '</b>';
}());

// ============================================================
// Chart 3: Grouped bar MIPS + M3 (reference level)
// ============================================================
(function () {
  const d = DATA.c3;
  const eff    = d.eff_annots;
  const annots = {};
  d.labels.forEach(function (a) {
    annots['eff_' + a] = {
      type: 'label',
      xValue: a,
      yValue: eff[a].yValue,
      yScaleID: 'yMips',
      content: eff[a].content,
      color: eff[a].color,
      font: { size: 11, weight: 'bold' },
      backgroundColor: 'transparent',
      borderWidth: 0,
    };
  });

  new Chart(document.getElementById('c3'), {
    type: 'bar',
    data: {
      labels: d.labels,
      datasets: [
        {
          label: 'Total ΔMIPS Allocated',
          data: d.mips,
          backgroundColor: 'rgba(155,89,182,0.75)',
          borderColor: '#8e44ad', borderWidth: 2, borderRadius: 5,
          yAxisID: 'yMips',
        },
        {
          label: 'M3: SFC Violations Resolved',
          data: d.m3,
          backgroundColor: 'rgba(241,196,15,0.82)',
          borderColor: '#f39c12', borderWidth: 2, borderRadius: 5,
          yAxisID: 'yM3',
        }
      ]
    },
    options: {
      responsive: true,
      plugins: {
        legend: { position: 'top' },
        tooltip: { mode: 'index' },
        subtitle: {
          display: true, text: d.subtitle,
          color: '#27ae60', font: { size: 12, weight: 'bold' }, padding: { bottom: 8 }
        },
        annotation: { annotations: annots }
      },
      scales: {
        yMips: {
          type: 'linear', position: 'left', beginAtZero: true, max: d.y_max,
          title: { display: true, text: 'ΔMIPS Allocated' },
          grid:  { color: 'rgba(155,89,182,0.1)' }
        },
        yM3: {
          type: 'linear', position: 'right', beginAtZero: true,
          title: { display: true, text: 'M3 = Σ DC3 (SFC violations resolved)' },
          grid:  { drawOnChartArea: false }
        }
      }
    }
  });

  document.getElementById('legend3').innerHTML = d.legend;
}());

// ============================================================
// Chart 4: SFC violations resolved vs cumulative MIPS budget
// ============================================================
(function () {
  const d       = DATA.c4;
  const ticks   = d.mips_ticks;
  const datasets = [];

  d.series.forEach(function (s) {
    datasets.push({
      type: 'line',
      label: s.label,
      data: ticks.map(function (t, i) { return { x: t, y: s.tick_vals[i] }; }),
      borderColor: s.line_color, backgroundColor: s.fill_color,
      fill: true, tension: 0.35, pointRadius: 0, pointHoverRadius: 0,
      borderWidth: 2.5, order: 2,
    });
    datasets.push({
      type: 'scatter',
      label: s.label + '__dot',
      data: ticks.slice(1).map(function (t, i) { return { x: t, y: s.tick_vals[i + 1] }; }),
      backgroundColor: s.line_color, borderColor: '#fff', borderWidth: 2,
      pointRadius: 8, pointHoverRadius: 10, order: 1,
    });
  });

  new Chart(document.getElementById('c4'), {
    type: 'scatter',
    data: { datasets: datasets },
    options: {
      responsive: true,
      interaction: { mode: 'index', axis: 'x', intersect: false },
      plugins: {
        legend: {
          position: 'top',
          labels: { filter: function (item) { return !item.text.endsWith('__dot'); } }
        },
        tooltip: {
          mode: 'index', axis: 'x', intersect: false,
          filter: function (item) { return !item.dataset.label.endsWith('__dot'); },
          callbacks: {
            title: function (items) { return 'MIPS budget: ' + items[0].parsed.x; },
            label: function (ctx) {
              return ctx.dataset.label + ': ' + ctx.parsed.y.toFixed(1) + ' SFC resolved';
            }
          }
        }
      },
      scales: {
        x: {
          type: 'linear', min: 0, max: 720,
          title: { display: true, text: 'Ngưỡng MIPS (tổng MIPS được phép dùng)' }
        },
        y: {
          min: 0, max: 36,
          title: { display: true, text: 'SFC violations resolved' },
          ticks: { stepSize: 4 }
        }
      }
    }
  });

  document.getElementById('legend4').innerHTML =
    d.series.map(function (s) {
      const final = s.tick_vals[s.tick_vals.length - 1];
      return '<b style="color:' + s.line_color + '">' + s.label + '</b>: ' +
             final.toFixed(0) + ' SFC @ ' + ticks[ticks.length - 1] + ' MIPS';
    }).join('   |   ');
}());
</script>
</body>
</html>
"""

# ── main ─────────────────────────────────────────────────────────────────────

def main():
    print("generate_chart.py -- reading 12 log files from:", LOG_DIR)
    data = collect_all(LOG_DIR)

    print(f"\n  {'Algo':<14} {'Level':>5} {'Events':>7} {'M2':>7} {'M3':>5} {'MIPS':>8} {'Eff':>7}")
    print("  " + "-" * 58)
    for algo in ALGOS:
        for lv in LEVELS:
            d = data[algo][lv]
            if d is None:
                print(f"  {algo:<14} {lv:>5} {'N/A':>7} {'N/A':>7} {'N/A':>5} {'N/A':>8} {'N/A':>7}")
            else:
                print(f"  {algo:<14} {lv:>5} {len(d['events']):>7} {d['m2']:>7.2f} "
                      f"{d['m3']:>5.0f} {d['total_mips']:>8.1f} {d['efficiency']:>7.3f}")

    payload = build_payload(data)
    html    = HTML_TEMPLATE.replace("__CHART_DATA__", json.dumps(payload, indent=2))

    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write(html)

    print(f"\n  Written: {OUTPUT}")


if __name__ == "__main__":
    main()
