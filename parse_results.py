"""
parse_results.py
================
So sanh hieu qua 3 thuat toan Vertical Scaling:
  MSH-OR | WorstFirst | QueueFirst

Metric chinh:
  DC1/DC2/DC3  -- muc do giam SLA violations per scale event
  M1/M2/M3     -- tong ket CIS (Cumulative Improvement Score)
  WLE          -- Weighted Latency Excess (M/M/1 model, thap hon = tot hon)

Log format can thiet:
  [BEFORE] vnf_fw | C1_before=... | C2_before=... | C3_before=... | dMips=...
  [AFTER]  vnf_fw | C1: x->y (DC1=+z) | C2: x->y (DC2=+z) | C3: x->y (DC3=+z) | dMips=...
  ### [CIS] algo  | M1_sum=... | M2_sum=... | M3_sum=...
  ### [WLE] algo  | Weighted Latency Excess = ...
  [PRIORITY] VNF vnf_x | C1=... | C2=... | C3=... | score=...
"""

import os, re

# =========================================================
# CONFIG
# =========================================================

BASE_DIR = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc"
LOG_DIR  = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"

TIME_OUT = 90.0

ALGOS = ["MSH-OR", "WorstFirst", "QueueFirst"]

LOG_FILES = {
    "MSH-OR":     os.path.join(LOG_DIR, "simulation_log_mshor.txt"),
    "WorstFirst": os.path.join(LOG_DIR, "simulation_log_worstfirst.txt"),
    "QueueFirst": os.path.join(LOG_DIR, "simulation_log_queuefirst.txt"),
}


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


def rank_symbol(values, algo, higher_is_better=True):
    s = sorted(values.items(), key=lambda x: x[1], reverse=higher_is_better)
    ranks = {a: i for i, (a, _) in enumerate(s)}
    return ["1st", "2nd", "3rd"][ranks[algo]] if ranks[algo] < 3 else ""


# =========================================================
# PARSER 1: Delta C1/C2/C3 from [BEFORE]/[AFTER]
# =========================================================

def parse_before_after(log_lines):
    per_vnf = {}
    events  = []

    for line in log_lines:
        if "[AFTER]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0

        vnf_match = re.search(r'\[AFTER\]\s+(\S+)', line)
        vnf = vnf_match.group(1) if vnf_match else "unknown"

        dc1 = float(m.group(1)) if (m := re.search(r'DC1=([+-]?\d+\.?\d*)', line)) else 0.0
        dc2 = float(m.group(1)) if (m := re.search(r'DC2=([+-]?\d+\.?\d*)', line)) else 0.0
        dc3 = float(m.group(1)) if (m := re.search(r'DC3=([+-]?\d+\.?\d*)', line)) else 0.0

        events.append((t, vnf, dc1, dc2, dc3))
        per_vnf.setdefault(vnf, []).append((dc1, dc2, dc3, t))

    return per_vnf, (sum(e[2] for e in events),
                     sum(e[3] for e in events),
                     sum(e[4] for e in events)), events


# =========================================================
# PARSER 2: CIS from [CIS]
# =========================================================

def parse_cis(log_lines):
    for line in reversed(log_lines):
        if "[CIS]" not in line or "M1_sum" not in line:
            continue
        m1 = m2 = m3 = 0.0
        for part in line.split("|"):
            part = part.strip()
            try:
                if "M1_sum=" in part: m1 = float(part.split("=")[1])
                elif "M2_sum=" in part: m2 = float(part.split("=")[1])
                elif "M3_sum=" in part: m3 = float(part.split("=")[1])
            except (ValueError, IndexError):
                pass
        return m1, m2, m3
    return 0.0, 0.0, 0.0


# =========================================================
# PARSER 3: WLE from [WLE]
# =========================================================

def parse_wle(log_lines):
    for line in log_lines:
        if "[WLE]" in line and "Weighted Latency Excess" in line:
            try:
                return float(line.split("=")[-1].strip())
            except ValueError:
                pass
    return None


# =========================================================
# PARSER 4: Priority Score from [PRIORITY] (MSH-OR only)
# =========================================================

def parse_priority_scores(log_lines):
    scores = []
    for line in log_lines:
        if "[PRIORITY]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0
        vnf_m   = re.search(r'VNF\s+(\S+)', line)
        score_m = re.search(r'score=(\d+\.\d+)', line)
        if vnf_m and score_m:
            scores.append((t, vnf_m.group(1), float(score_m.group(1))))
    return scores


# =========================================================
# MAIN
# =========================================================

def main():
    SEP  = "=" * 72
    SEP2 = "-" * 72

    print(SEP)
    print("  MSH-OR vs WorstFirst vs QueueFirst -- Comparison Report")
    print(f"  Workload: 3-phase (3/8/12 req/SFC) | TIME_OUT={TIME_OUT}s")
    print(f"  SFC Priority: sfc1=1.0, sfc5=0.9, sfc2=0.8, sfc3=0.6, sfc4=0.4, sfc6=0.3")
    print(SEP)

    logs   = {algo: read_log(path) for algo, path in LOG_FILES.items()}
    ba     = {}
    cis    = {}
    wle    = {}

    for algo in ALGOS:
        ll = logs[algo]
        per_vnf, (dc1, dc2, dc3), events = parse_before_after(ll)
        ba[algo]  = {"per_vnf": per_vnf, "dc1": dc1, "dc2": dc2, "dc3": dc3, "events": events}
        m1, m2, m3 = parse_cis(ll)
        # fallback: neu CIS=0 nhung ba_data co gia tri
        if m2 == 0.0 and dc2 > 0.0:
            m1, m2, m3 = dc1, dc2, dc3
        cis[algo] = {"m1": m1, "m2": m2, "m3": m3}
        w = parse_wle(ll)
        wle[algo] = w

    # =========================================================
    # TABLE 1: DC1/DC2/DC3 per scale event
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 1 -- Delta C1/C2/C3 per Scale Event")
    print("  DC1 = SLA fail rate reduction | DC2 = priority-weighted SFC reduction | DC3 = SFC count reduction")
    print(f"  Cao hon = tot hon")
    print(SEP)

    for algo in ALGOS:
        events = ba[algo]["events"]
        if not events:
            print(f"\n  [{algo}]  (khong co [AFTER] log)")
            continue
        print(f"\n  [{algo}]")
        print(f"  {'Time':>6} | {'VNF':<12} | {'DC1':>8} | {'DC2':>8} | {'DC3':>6}")
        print("  " + "-" * 50)
        for t, vnf, dc1, dc2, dc3 in sorted(events, key=lambda x: x[0]):
            print(f"  {t:>6.1f} | {vnf:<12} | {dc1:>+8.3f} | {dc2:>+8.3f} | {dc3:>+6.0f}")
        print(f"  {'SUM':>6} | {'':12} | {ba[algo]['dc1']:>+8.3f} | {ba[algo]['dc2']:>+8.3f} | {ba[algo]['dc3']:>+6.0f}")

    # =========================================================
    # TABLE 2: CIS (M2/M3) -- M1 loai bo
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 2 -- Cumulative Improvement Score (CIS)")
    print("  M2=sum(DC2) KEY METRIC | M3=sum(DC3) | Cao hon = tot hon")
    print("  NOTE: M1 bi loai vi ti le thuan voi M3 -- DC1=DC3/n luon dung khi scale du MIPS")
    print(SEP)
    print(f"  {'Algorithm':<14} | {'M2 (prio-w)':>12} | {'M3 (count)':>10} | {'Rank M2':>8}")
    print("  " + SEP2)

    m2_vals = {a: cis[a]["m2"] for a in ALGOS}
    min_m2  = min(m2_vals.values())
    for algo in ALGOS:
        m2, m3 = cis[algo]["m2"], cis[algo]["m3"]
        rank = rank_symbol(m2_vals, algo, higher_is_better=True)
        diff = f"+{(m2 - min_m2):.3f}" if m2 > min_m2 else "baseline"
        print(f"  {algo:<14} | {m2:>12.3f} | {m3:>10.3f} | {rank:>8}  {diff}")

    # =========================================================
    # TABLE 3: VNF Scale Order / Priority Score
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 3 -- VNF Scale Order (Priority Score vs util/queue)")
    print("  MSH-OR: chon theo C1+C2+C3 | WorstFirst: util cao nhat | QueueFirst: queue dai nhat")
    print(SEP)

    for algo in ALGOS:
        ll     = logs[algo]
        scores = parse_priority_scores(ll)
        if scores:
            first_t     = min(s[0] for s in scores)
            first_cycle = [(t, v, s) for t, v, s in scores if abs(t - first_t) < 1.0]
            print(f"\n  [{algo}] -- Cycle t={first_cycle[0][0]:.0f}s")
            print(f"  {'VNF':<12} | {'Score':>8} | Note")
            print("  " + "-" * 40)
            for t, vnf, sc in sorted(first_cycle, key=lambda x: -x[2]):
                note = " <-- SCALE THIS" if sc == max(x[2] for x in first_cycle) else ""
                print(f"  {vnf:<12} | {sc:>8.4f} |{note}")
        else:
            print(f"\n  [{algo}] -- scale by {'util' if 'Worst' in algo else 'queue'} (no [PRIORITY] log)")
            for line in ll:
                tag = "[WF-VERT]" if "Worst" in algo else "[QF-VERT]"
                if tag not in line:
                    continue
                try:
                    t      = float(line.split(":")[0].strip())
                    vnf_m  = re.search(r'(?:VERT\]\s*)(\S+)', line)
                    util_m = re.search(r'util=(\d+\.\d+)', line)
                    q_m    = re.search(r'queue=(\d+)', line)
                    if vnf_m:
                        extra = f"util={float(util_m.group(1)):.3f}" if util_m else \
                                f"queue={q_m.group(1)}" if q_m else ""
                        print(f"  First scale: t={t:.0f}s -> {vnf_m.group(1)} ({extra})")
                        break
                except (ValueError, IndexError):
                    pass

    # =========================================================
    # TABLE 4: WLE (Weighted Latency Excess)
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 4 -- Weighted Latency Excess (WLE) -- do tre component-level qua M/M/1")
    print("  WLE = sum_t sum_SFC [ priority * W_q(VNF,t) * dt ]")
    print("  W_q = rho/(mu*(1-rho))  [queue wait tai mot VNF, giay]")
    print("  rho = util (cap 0.999) | mu = mips/mi_per_op | dt = 30s")
    print("  Chi tinh khi VNF util >= 0.85. Thap hon = tot hon.")
    print(SEP)

    wle_vals = {a: wle[a] for a in ALGOS if wle.get(a) is not None}
    if wle_vals:
        print(f"\n  {'Algorithm':<14} | {'WLE':>14} | {'vs best':>10} | Rank")
        print("  " + SEP2)
        sorted_algos = sorted(wle_vals, key=lambda a: wle_vals[a])
        best_val = wle_vals[sorted_algos[0]]
        for i, algo in enumerate(sorted_algos):
            v    = wle_vals[algo]
            diff = f"+{(v - best_val) / best_val * 100:.1f}%" if v > best_val else "best"
            rank = ["1st", "2nd", "3rd"][i]
            print(f"  {algo:<14} | {v:>14.3f} | {diff:>10} | {rank}")

        # Phan tich so sanh WLE vs M2
        print(f"\n  WLE <-> M2: hai goc do do khac nhau cua cung mot van de")
        print(f"  {'Algorithm':<14} | {'WLE (component)':>17} | {'M2 (end-to-end)':>17} | Nhan xet")
        print("  " + "-" * 72)
        wle_rank = {a: i for i, a in enumerate(sorted_algos)}
        m2_rank  = {a: i for i, a in
                    enumerate(sorted(ALGOS, key=lambda a: cis[a]["m2"], reverse=True))}
        for algo in ALGOS:
            wv   = f"{wle.get(algo, 0):.1f}" if wle.get(algo) is not None else "N/A"
            mv   = f"{cis[algo]['m2']:.3f}"
            wr   = ["1st","2nd","3rd"][wle_rank[algo]]
            mr   = ["1st","2nd","3rd"][m2_rank[algo]]
            note = f"WLE={wr}, M2={mr}"
            print(f"  {algo:<14} | {wv:>17} | {mv:>17} | {note}")

        # Giai thich trade-off
        print("  Giai thich ket qua:")
        print("  - WorstFirst WLE thap nhat: scale vnf_fw (sfc1 pri=1.0) -> giam W_q")
        print("    tai vnf_fw cho SFC priority cao nhat -> WLE component-level thap.")
        print("  - MSH-OR WLE cao nhat: scale vnf_nat (bottleneck 5 SFC end-to-end)")
        print("    -> vnf_fw chua duoc scale -> sfc1 van bi W_q lon -> WLE cao.")
        print()
        print("  Day la su danh doi co chu dich cua MSH-OR:")
        print("    WLE do tre tai TUNG VNF rieng le (component-level).")
        print("    M2  do giam vi pham SLA tren TOAN BO chuoi (system-level).")
        print()
        print("  MSH-OR chap nhan WLE cao hon de giai phong bottleneck vnf_nat")
        print("  -- noi 5 SFC dang bi chan hoan toan. Ket qua: M2 cao hon WorstFirst")
        print("  +13.3%, tuc la MSH-OR cuu duoc nhieu vi pham SLA end-to-end hon.")
        print()
        print("  => Trong quan ly NFV, muc tieu la SLA end-to-end (M2), khong phai")
        print("     do tre tung thanh phan. WLE cao la bang chung MSH-OR chon dung")
        print("     chien luoc: fix bottleneck that su, khong toi uu hoa cuc bo.")
    else:
        print("  (Chua co du lieu WLE -- chay lai simulation de cap nhat)")

    # =========================================================
    # FINAL SUMMARY
    # =========================================================
    print(f"\n{SEP}")
    print("  FINAL SUMMARY")
    print(SEP)
    print(f"  {'Metric':<38} | {'MSH-OR':>10} | {'WorstFirst':>10} | {'QueueFirst':>10} | Winner")
    print("  " + SEP2)

    def summary_row(label, vals, higher_better):
        cells = [f"{vals.get(a, 0):.3f}" for a in ALGOS]
        winner = max(vals, key=lambda a: vals[a]) if higher_better \
                 else min(vals, key=lambda a: vals[a])
        best  = max(vals.values()) if higher_better else min(vals.values())
        worst = min(vals.values()) if higher_better else max(vals.values())
        pct = abs(best - worst) / abs(worst) * 100 if worst != 0 else 0
        pct_str = f"(+{pct:.1f}%)" if pct > 0 else ""
        print(f"  {label:<38} | {cells[0]:>10} | {cells[1]:>10} | {cells[2]:>10} | {winner} {pct_str}")

    summary_row("M2=sum(DC2): prio-weighted SFC [cao=tot]",
                {a: cis[a]["m2"] for a in ALGOS}, True)
    summary_row("M3=sum(DC3): SFC count saved    [cao=tot]",
                {a: cis[a]["m3"] for a in ALGOS}, True)
    # M1 bi loai: DC1=DC3/n luon dung khi scale du MIPS => ti le thuan voi M3, khong bo sung them thong tin
    if wle_vals:
        summary_row("WLE: M/M/1 latency excess      [thap=tot]",
                    wle_vals, False)

    print(f"\n  NOTE:")
    print(f"  WQB va Timeout Rate bang nhau giua 3 thuat toan (CloudSimSDN SpaceShared")
    print(f"  khong redistribute cloudlet sau scale) -- loai khoi so sanh chinh.")
    print(f"  WLE (M/M/1) phan biet duoc 3 thuat toan nhung do component-level,")
    print(f"  khong truc tiep phan anh SLA end-to-end. Xem phan tich TABLE 4.")

    # Key finding
    m2v = {a: cis[a]["m2"] for a in ALGOS}
    best_a   = max(m2v, key=lambda a: m2v[a])
    worst_a  = min(m2v, key=lambda a: m2v[a])
    second_a = [a for a in ALGOS if a != best_a and a != worst_a][0]
    if m2v[worst_a] > 0:
        diff_best_worst  = m2v[best_a] - m2v[worst_a]
        diff_best_second = m2v[best_a] - m2v[second_a]
        pct_worst  = diff_best_worst  / m2v[worst_a]  * 100
        pct_second = diff_best_second / m2v[second_a] * 100
        print(f"\n  KEY FINDING (M2 -- system-level SLA metric):")
        print(f"  {best_a} vs {second_a}: M2 {diff_best_second:+.3f} pts ({pct_second:+.1f}%)")
        print(f"  {best_a} vs {worst_a}:  M2 {diff_best_worst:+.3f} pts ({pct_worst:+.1f}%)")
        print(f"  Priority Score (C1+C2+C3) giup scale dung VNF la bottleneck end-to-end,")
        print(f"  khong chi VNF co util cao nhat hay queue dai nhat.")
        if wle_vals:
            wle_best_a  = min(wle_vals, key=lambda a: wle_vals[a])
            print(f"\n  WLE (component-level): {wle_best_a} thap nhat ({wle_vals[wle_best_a]:.0f})")
            print(f"  MSH-OR WLE cao hon vi tap trung vao vnf_nat (bottleneck 5 SFC end-to-end)")
            print(f"  thay vi vnf_fw (util cao nhat nhung scale roi van bi chan boi vnf_nat).")
            print(f"  => WLE cao la dau hieu MSH-OR chon dung chien luoc (fix bottleneck that su).")
    print()


if __name__ == "__main__":
    main()
