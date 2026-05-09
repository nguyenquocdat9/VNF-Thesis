"""
chart.py
========
3 bieu do so sanh MSH-OR vs WorstFirst vs QueueFirst.
Output: results_chart.png (3 subplots, 1 file)
"""

import os
import re
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

LOG_DIR  = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
OUT_FILE = os.path.join(LOG_DIR, "results_chart.png")

LOG_FILES = {
    "MSH-OR":     os.path.join(LOG_DIR, "simulation_log_mshor.txt"),
    "WorstFirst": os.path.join(LOG_DIR, "simulation_log_worstfirst.txt"),
    "QueueFirst": os.path.join(LOG_DIR, "simulation_log_queuefirst.txt"),
}

ALGOS  = ["MSH-OR", "WorstFirst", "QueueFirst"]
COLORS = ["#2196F3", "#FF9800", "#4CAF50"]   # blue, orange, green


# =========================================================
# HELPERS
# =========================================================

def read_log(path):
    for enc in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            with open(path, encoding=enc, errors="replace") as f:
                return f.readlines()
        except FileNotFoundError:
            return []
    return []


def parse_cis(log_lines):
    """Parse final CIS line: returns (m2, m3)."""
    for line in reversed(log_lines):
        if "[CIS]" not in line or "M2_sum" not in line:
            continue
        m2 = m3 = 0.0
        for part in line.split("|"):
            p = part.strip()
            try:
                if "M2_sum=" in p:
                    m2 = float(p.split("=")[1])
                elif "M3_sum=" in p:
                    m3 = float(p.split("=")[1])
            except (ValueError, IndexError):
                pass
        return m2, m3
    return 0.0, 0.0


def parse_after_events(log_lines):
    """Returns sorted list of (time, vnf, dmips, dc2, dc3) from [AFTER] lines."""
    events = []
    for line in log_lines:
        if "[AFTER]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0
        vnf_m = re.search(r'\[AFTER\]\s+(\S+)', line)
        dc2_m = re.search(r'DC2=([+-]?\d+\.?\d*)', line)
        dc3_m = re.search(r'DC3=([+-]?\d+\.?\d*)', line)
        dm_m  = re.search(r'dMips=(\d+\.?\d*)', line)
        vnf   = vnf_m.group(1) if vnf_m else "unknown"
        dc2   = float(dc2_m.group(1)) if dc2_m else 0.0
        dc3   = float(dc3_m.group(1)) if dc3_m else 0.0
        dmips = float(dm_m.group(1)) if dm_m else 0.0
        events.append((t, vnf, dmips, dc2, dc3))
    return sorted(events, key=lambda x: x[0])


def cum_m2_at(events, timepoints):
    """Cumulative sum of DC2 at each timepoint."""
    return [sum(dc2 for t, _, _, dc2, _ in events if t <= tp) for tp in timepoints]


# =========================================================
# MAIN
# =========================================================

def main():
    logs   = {a: read_log(LOG_FILES[a]) for a in ALGOS}
    events = {a: parse_after_events(logs[a]) for a in ALGOS}
    cis    = {a: parse_cis(logs[a]) for a in ALGOS}

    # Derived data
    m2_vals    = [cis[a][0] for a in ALGOS]
    m3_vals    = [cis[a][1] for a in ALGOS]
    total_mips = [sum(e[2] for e in events[a]) for a in ALGOS]

    timepoints = [60, 120, 150, 180, 210]
    cum_m2     = {a: cum_m2_at(events[a], timepoints) for a in ALGOS}

    # =========================================================
    # Figure layout
    # =========================================================
    fig, axes = plt.subplots(1, 3, figsize=(20, 7))
    fig.suptitle(
        "MSH-OR vs WorstFirst vs QueueFirst — Performance Comparison",
        fontsize=17, fontweight='bold', y=1.02
    )

    x         = np.arange(len(ALGOS))
    bar_width = 0.5

    # ----------------------------------------------------------
    # Chart 1: Bar chart -- M2 comparison
    # ----------------------------------------------------------
    ax1   = axes[0]
    bars1 = ax1.bar(x, m2_vals, width=bar_width, color=COLORS,
                    edgecolor='black', linewidth=0.9)
    ax1.set_title("Chart 1\nPriority-Weighted SLA Improvement (M2)",
                  fontsize=13, fontweight='bold', pad=10)
    ax1.set_ylabel("M2 = sum(DC2)  [higher is better]", fontsize=12)
    ax1.set_xticks(x)
    ax1.set_xticklabels(ALGOS, fontsize=12)
    ax1.set_ylim(0, max(m2_vals) * 1.30)
    ax1.grid(axis='y', alpha=0.35, linestyle='--')
    ax1.spines['top'].set_visible(False)
    ax1.spines['right'].set_visible(False)

    best_m2 = max(m2_vals)
    for bar, val, algo in zip(bars1, m2_vals, ALGOS):
        # Value label on top
        ax1.text(bar.get_x() + bar.get_width() / 2,
                 bar.get_height() + 0.15,
                 f"{val:.1f}", ha='center', va='bottom',
                 fontsize=13, fontweight='bold')
        # % gap label inside bar (for non-best)
        if val < best_m2:
            pct = (best_m2 - val) / best_m2 * 100
            ax1.text(bar.get_x() + bar.get_width() / 2,
                     bar.get_height() / 2,
                     f"−{pct:.1f}%\nvs best",
                     ha='center', va='center',
                     fontsize=10, color='white', fontweight='bold')

    # Annotate MSH-OR improvement arrows
    ax1.annotate("", xy=(x[0], m2_vals[0]), xytext=(x[1], m2_vals[1]),
                 arrowprops=dict(arrowstyle='<->', color='#555555', lw=1.4))
    ax1.text((x[0]+x[1])/2, (m2_vals[0]+m2_vals[1])/2 + 0.5,
             f"+{(m2_vals[0]-m2_vals[1])/m2_vals[1]*100:.1f}%",
             ha='center', va='bottom', fontsize=10, color='#333333')

    # ----------------------------------------------------------
    # Chart 2: Dual-axis -- MIPS spent (bar) vs SFC saved (line)
    # ----------------------------------------------------------
    ax2 = axes[1]
    bars2 = ax2.bar(x, total_mips, width=bar_width, color=COLORS,
                    edgecolor='black', linewidth=0.9, alpha=0.82)
    ax2.set_title("Chart 2\nMIPS Efficiency: Resources Spent vs SFC Saved",
                  fontsize=13, fontweight='bold', pad=10)
    ax2.set_ylabel("Total MIPS Allocated  [lower = more efficient]", fontsize=12)
    ax2.set_xticks(x)
    ax2.set_xticklabels(ALGOS, fontsize=12)
    ax2.set_ylim(0, max(total_mips) * 1.40)
    ax2.grid(axis='y', alpha=0.35, linestyle='--')
    ax2.spines['top'].set_visible(False)

    for bar, val in zip(bars2, total_mips):
        ax2.text(bar.get_x() + bar.get_width() / 2,
                 bar.get_height() + 8,
                 f"{val:.0f}", ha='center', va='bottom', fontsize=12)

    # Right axis: M3 (total SFC saved) as line+markers
    ax2r = ax2.twinx()
    ax2r.plot(x, m3_vals, 'D-', color='#E53935', linewidth=2.8,
              markersize=11, zorder=5)
    ax2r.set_ylabel("M3 = Total SFC Saved  [higher is better]",
                    fontsize=12, color='#E53935')
    ax2r.set_ylim(0, max(m3_vals) * 1.50)
    ax2r.tick_params(axis='y', labelcolor='#E53935', labelsize=11)
    ax2r.spines['top'].set_visible(False)

    for xi, val in zip(x, m3_vals):
        ax2r.text(xi, val + 0.6, f"{val:.0f} SFC",
                  ha='center', va='bottom',
                  fontsize=11, color='#E53935', fontweight='bold')

    # Legend for Chart 2
    h_bar  = mpatches.Patch(color='#888888', alpha=0.8, label='MIPS Used (bars)')
    h_line = mpatches.Patch(color='#E53935', label='SFC Saved M3 (line)')
    ax2.legend(handles=[h_bar, h_line], loc='upper right', fontsize=10)

    # ----------------------------------------------------------
    # Chart 3: Line chart -- Cumulative M2 over time
    # ----------------------------------------------------------
    ax3     = axes[2]
    markers = ['o', 's', '^']
    lstyles = ['-', '--', ':']

    for i, algo in enumerate(ALGOS):
        vals = cum_m2[algo]
        ax3.plot(timepoints, vals,
                 marker=markers[i], linestyle=lstyles[i],
                 color=COLORS[i], linewidth=2.8, markersize=10,
                 label=algo)
        # Endpoint label
        ax3.text(timepoints[-1] + 3, vals[-1],
                 f"{vals[-1]:.1f}", va='center',
                 fontsize=11, color=COLORS[i], fontweight='bold')

    ax3.set_title("Chart 3\nCumulative M2 over Simulation Time",
                  fontsize=13, fontweight='bold', pad=10)
    ax3.set_xlabel("Simulation Time (s)", fontsize=12)
    ax3.set_ylabel("Cumulative M2  [higher is better]", fontsize=12)
    ax3.set_xticks(timepoints)
    ax3.set_xticklabels([str(t) for t in timepoints], fontsize=11)
    ax3.set_xlim(50, 230)
    ax3.set_ylim(0, max(max(v) for v in cum_m2.values()) * 1.25)
    ax3.legend(fontsize=12, loc='upper left')
    ax3.grid(alpha=0.35, linestyle='--')
    ax3.spines['top'].set_visible(False)
    ax3.spines['right'].set_visible(False)

    # =========================================================
    # Save
    # =========================================================
    plt.tight_layout()
    plt.savefig(OUT_FILE, dpi=150, bbox_inches='tight')
    print(f"Saved: {OUT_FILE}")


if __name__ == "__main__":
    main()
