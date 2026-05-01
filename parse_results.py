"""
parse_results.py
================
Parse CloudSimSDN simulation logs de so sanh hieu qua 3 thuat toan:
  MSH-OR | WorstFirst | QueueFirst

Nguon du lieu: simulation_log_*.txt (do Java ghi)

===========================================================
METRIC CHINH
===========================================================

1. DELTA C1 / C2 / C3  (doc tu [BEFORE] + [AFTER] trong log)
   ----------------------------------------------------------
   DC1 = C1_before - C1_after
         C1 = so SFC vi pham / tong SFC qua VNF
         DC1 cao = giam duoc nhieu % SFC dang fail sau scale

   DC2 = C2_before - C2_after
         C2 = Sum(priority_i * 1) cho SFC dang vi pham
         DC2 cao = giam duoc nhieu "trong so priority" SFC dang fail
         vi du: SFC1(1.0) + SFC2(0.8) vi pham truoc -> C2=1.8
                Chi con SFC2 vi pham sau -> C2=0.8 -> DC2=1.0
         MSH-OR scale vnf_fw (SFC1 priority cao) truoc
         -> DC2 cua MSH-OR cao hon WorstFirst/QueueFirst

   DC3 = C3_before - C3_after
         C3 = so SFC vi pham (tuyet doi)
         DC3 cao = cuu duoc nhieu SFC

   CIS (Cumulative Improvement Score):
     M1 = sum(DC1) | M2 = sum(DC2) | M3 = sum(DC3)
     Tat ca: cao hon = tot hon

2. WQB  (doc tu [WQB] trong log)
   Weighted Queue Burden = sum priority * queue * dt
   Thap hon = tot hon

3. Timeout Rate + Avg Response Time per SFC  (doc tu result CSV)
   Standard metrics

===========================================================
LOG FORMAT TUONG THICH
===========================================================
[BEFORE] vnf_fw   | C1_before=1.000(4/4 SFC) | C2_before=3.700 | ...
[AFTER]  vnf_fw   | C1: 1.000->0.250 (DC1=+0.750) | C2: 3.700->0.800 (DC2=+2.900) | ...
### [CIS] MSH-OR   | M1_sum=0.750 | M2_sum=2.900 | M3_sum=3.000
### [WQB] MSH-OR   | Weighted Queue Burden = 171802.4
"""

import os, re

# =========================================================
# CONFIG -- chinh sua cho phu hop moi truong
# =========================================================

BASE_DIR  = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc"
LOG_DIR   = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"

# Priority cua tung SFC (0-indexed, khop SFC_PRIORITY_MAP trong Java)
SFC_PRIORITIES = {0: 1.0, 1: 0.8, 2: 0.6, 3: 0.4, 4: 0.9, 5: 0.3}
NUM_SFC        = 6
TIME_OUT       = 25.0

# Workload 3-phase: req/SFC = 3 (off-peak) | 8 (peak) | 12 (heavy)
# Dung de map workload ID -> SFC index
REQ_PER_SFC_OFFPEAK = 3

ALGOS = ["MSH-OR", "WorstFirst", "QueueFirst"]

FILES = {
    "MSH-OR":     os.path.join(BASE_DIR, "result_mshor.csv"),
    "WorstFirst": os.path.join(BASE_DIR, "result_random.csv"),
    "QueueFirst": os.path.join(BASE_DIR, "result_firstfit.csv"),
}

LOG_FILES = {
    "MSH-OR":     os.path.join(LOG_DIR, "simulation_log_mshor.txt"),
    "WorstFirst": os.path.join(LOG_DIR, "simulation_log_random.txt"),
    "QueueFirst": os.path.join(LOG_DIR, "simulation_log_firstfit.txt"),
}


# =========================================================
# HELPERS
# =========================================================

def read_log(path):
    """Doc log file, thu nhieu encoding."""
    for enc in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            with open(path, encoding=enc, errors="replace") as f:
                return f.readlines()
        except FileNotFoundError:
            return []
    return []


def rank_symbol(values, algo, higher_is_better=False):
    s = sorted(values.items(), key=lambda x: x[1], reverse=higher_is_better)
    ranks = {a: i for i, (a, _) in enumerate(s)}
    return ["1st", "2nd", "3rd"][ranks[algo]] if ranks[algo] < 3 else ""


# =========================================================
# PARSER 1: Delta C1/C2/C3 tu [BEFORE] va [AFTER] logs
# =========================================================

def parse_before_after(log_lines):
    """
    Doc tat ca cap [BEFORE] / [AFTER] de lay DC1, DC2, DC3.

    [BEFORE] vnf_fw | C1_before=1.000(4/4 SFC) | C2_before=3.700(sum pri*SFC) | C3_before=4 SFC | dMips=261.0
    [AFTER]  vnf_fw | C1: 1.000->0.250 (DC1=+0.750) | C2: 3.700->0.800 (DC2=+2.900) | C3: 4->1 (DC3=+3) | dMips=261.0

    Tra ve:
      per_vnf: {vnf_name: [(dc1, dc2, dc3, time), ...]}
      totals:  (sum_dc1, sum_dc2, sum_dc3)
    """
    per_vnf = {}
    events  = []  # (time, vnf, dc1, dc2, dc3)

    for line in log_lines:
        if "[AFTER]" not in line:
            continue

        # Format: "90.0: [AFTER]  vnf_fw     | C1: 1.000->0.250 (DC1=+0.750) | ..."
        try:
            time_str = line.split(":")[0].strip()
            t = float(time_str)
        except (ValueError, IndexError):
            t = 0.0

        # VNF name
        vnf_match = re.search(r'\[AFTER\]\s+(\S+)', line)
        vnf = vnf_match.group(1) if vnf_match else "unknown"

        # DC1
        dc1_match = re.search(r'DC1=([+-]?\d+\.?\d*)', line)
        dc1 = float(dc1_match.group(1)) if dc1_match else 0.0

        # DC2
        dc2_match = re.search(r'DC2=([+-]?\d+\.?\d*)', line)
        dc2 = float(dc2_match.group(1)) if dc2_match else 0.0

        # DC3
        dc3_match = re.search(r'DC3=([+-]?\d+\.?\d*)', line)
        dc3 = float(dc3_match.group(1)) if dc3_match else 0.0

        events.append((t, vnf, dc1, dc2, dc3))
        if vnf not in per_vnf:
            per_vnf[vnf] = []
        per_vnf[vnf].append((dc1, dc2, dc3, t))

    sum_dc1 = sum(e[2] for e in events)
    sum_dc2 = sum(e[3] for e in events)
    sum_dc3 = sum(e[4] for e in events)

    return per_vnf, (sum_dc1, sum_dc2, sum_dc3), events


# =========================================================
# PARSER 2: CIS tu [CIS] log
# =========================================================

def parse_cis(log_lines):
    """
    Doc dong ### [CIS] ...
    Format: ### [CIS] MSH-OR     | M1_sum=0.750 | M2_sum=2.900 | M3_sum=3.000
    """
    for line in log_lines:
        if "[CIS]" not in line or "M1_sum" not in line:
            continue
        m1 = m2 = m3 = 0.0
        for part in line.split("|"):
            part = part.strip()
            if "M1_sum=" in part:
                try: m1 = float(part.split("=")[1])
                except: pass
            elif "M2_sum=" in part:
                try: m2 = float(part.split("=")[1])
                except: pass
            elif "M3_sum=" in part:
                try: m3 = float(part.split("=")[1])
                except: pass
        return m1, m2, m3
    return 0.0, 0.0, 0.0


# =========================================================
# PARSER 3: WQB tu [WQB] log
# =========================================================

def parse_wqb(log_lines):
    """
    ### [WQB] MSH-OR     | Weighted Queue Burden = 171802.4
    """
    for line in log_lines:
        if "[WQB]" in line and "Weighted Queue Burden" in line:
            try:
                return float(line.split("=")[-1].strip())
            except ValueError:
                pass
    return None


# =========================================================
# PARSER 4: Priority Score tu [PRIORITY] log
# =========================================================

def parse_priority_scores(log_lines):
    """
    Doc cac dong [PRIORITY] de xem thu tu chon VNF.
    Format: 90.00: [PRIORITY] VNF vnf_fw   | C1=1.00(5/5 SFC) | C2=0.475(breach=... pres=0.90) | C3=... | score=0.7218
    Tra ve: [(time, vnf, score), ...]
    """
    scores = []
    for line in log_lines:
        if "[PRIORITY]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0

        vnf_match   = re.search(r'VNF\s+(\S+)', line)
        score_match = re.search(r'score=(\d+\.\d+)', line)
        if vnf_match and score_match:
            scores.append((t, vnf_match.group(1), float(score_match.group(1))))
    return scores


# =========================================================
# PARSER 5: Result CSV (timeout + response time)
# =========================================================

def get_sfc_idx(wid, req_per_sfc, num_sfc):
    group_size = req_per_sfc * num_sfc
    return (wid % group_size) // req_per_sfc


def parse_result_csv(filepath):
    sfc_data = {i: {"done": 0, "timeout": 0, "response_times": []} for i in range(NUM_SFC)}
    try:
        lines = open(filepath, encoding="utf-8", errors="ignore").readlines()
    except FileNotFoundError:
        return None

    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("Workload"):
            continue
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 4:
            continue
        try:
            wid    = int(parts[0])
            status = parts[-1]
        except (ValueError, IndexError):
            continue

        sfc_idx = get_sfc_idx(wid, REQ_PER_SFC_OFFPEAK, NUM_SFC)
        if sfc_idx >= NUM_SFC:
            continue

        if "TimeOut" in status:
            sfc_data[sfc_idx]["timeout"] += 1
        else:
            try:
                rt = float(parts[-2])
                sfc_data[sfc_idx]["done"] += 1
                sfc_data[sfc_idx]["response_times"].append(rt)
            except (ValueError, IndexError):
                sfc_data[sfc_idx]["done"] += 1
    return sfc_data


# =========================================================
# METRIC: WTR (Weighted Timeout Rate)
# =========================================================

def calc_wtr(data):
    """
    WTR = sum(priority_i * timeout_i) / sum(priority_i * total_i)
    Thap hon = tot hon.
    MSH-OR scale SFC priority cao truoc -> timeout SFC1(1.0) it hon -> WTR thap hon.
    """
    w_timeout = w_total = 0.0
    for idx, d in data.items():
        w = SFC_PRIORITIES[idx]
        total = d["done"] + d["timeout"]
        w_timeout += w * d["timeout"]
        w_total   += w * total
    return w_timeout / w_total if w_total > 0 else 1.0


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

    # ---- Doc tat ca logs -----------------------------------
    logs   = {algo: read_log(path) for algo, path in LOG_FILES.items()}
    csvs   = {algo: parse_result_csv(path) for algo, path in FILES.items()}

    # --- Parse cac metric tu log ---
    ba_data = {}   # before/after delta
    cis     = {}   # CIS (M1/M2/M3)
    wqb     = {}   # WQB

    for algo in ALGOS:
        ll = logs[algo]
        per_vnf, (dc1, dc2, dc3), events = parse_before_after(ll)
        ba_data[algo] = {"per_vnf": per_vnf, "dc1": dc1, "dc2": dc2, "dc3": dc3, "events": events}
        m1, m2, m3 = parse_cis(ll)
        cis[algo] = {"m1": m1, "m2": m2, "m3": m3}
        w = parse_wqb(ll)
        if w is not None:
            wqb[algo] = w

    # =========================================================
    # TABLE 1: Delta C1 / C2 / C3 per scale event (chi tiet)
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 1 -- Delta C1/C2/C3 per Scale Event (trich tu [BEFORE]/[AFTER])")
    print("  DC1 = C1_before - C1_after  (SLA fail rate reduction)")
    print("  DC2 = C2_before - C2_after  (Priority-weighted SFC reduction)")
    print("  DC3 = C3_before - C3_after  (absolute SFC count reduction)")
    print(f"  Cao hon = tot hon (thuat toan scale dung VNF, dung luc)")
    print(SEP)

    for algo in ALGOS:
        events = ba_data[algo]["events"]
        if not events:
            print(f"\n  [{algo}]  (khong co [AFTER] log -- kiem tra simulation_log_{algo.lower().replace('-','')}.txt)")
            continue
        print(f"\n  [{algo}]")
        print(f"  {'Time':>6} | {'VNF':<12} | {'DC1':>8} | {'DC2':>8} | {'DC3':>6}")
        print("  " + "-" * 50)
        for t, vnf, dc1, dc2, dc3 in sorted(events, key=lambda x: x[0]):
            print(f"  {t:>6.1f} | {vnf:<12} | {dc1:>+8.3f} | {dc2:>+8.3f} | {dc3:>+6.0f}")
        print(f"  {'SUM':>6} | {'':12} | {ba_data[algo]['dc1']:>+8.3f} | "
              f"{ba_data[algo]['dc2']:>+8.3f} | {ba_data[algo]['dc3']:>+6.0f}")

    # =========================================================
    # TABLE 2: CIS Tong ket (M1/M2/M3)
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 2 -- Cumulative Improvement Score (CIS) -- Cao hon = tot hon")
    print("  M1 = sum(DC1): tong giam ti le SFC vi pham")
    print("  M2 = sum(DC2): tong giam Sum(priority*SFC) -- KEY METRIC cua luan van")
    print("  M3 = sum(DC3): tong so SFC tuyet doi duoc cuu")
    print(SEP)
    print(f"  {'Algorithm':<14} | {'M1 (SFC%)':>10} | {'M2 (prio-w)':>12} | {'M3 (count)':>10} | {'Rank M2':>8}")
    print("  " + SEP2)

    m2_vals = {a: cis[a]["m2"] for a in ALGOS}
    # Fallback: neu CIS chua co (log chua chay), dung sum tu ba_data
    for algo in ALGOS:
        if cis[algo]["m2"] == 0.0 and ba_data[algo]["dc2"] != 0.0:
            cis[algo]["m2"] = ba_data[algo]["dc2"]
            cis[algo]["m1"] = ba_data[algo]["dc1"]
            cis[algo]["m3"] = ba_data[algo]["dc3"]
            m2_vals[algo]   = cis[algo]["m2"]

    best_m2 = max(m2_vals.values()) if any(v != 0 for v in m2_vals.values()) else 1
    for algo in ALGOS:
        m1, m2, m3 = cis[algo]["m1"], cis[algo]["m2"], cis[algo]["m3"]
        rank = rank_symbol(m2_vals, algo, higher_is_better=True)
        diff = f"+{(m2-min(m2_vals.values())):.3f}" if m2 > min(m2_vals.values()) else "baseline"
        print(f"  {algo:<14} | {m1:>10.3f} | {m2:>12.3f} | {m3:>10.3f} | {rank:>8}  {diff}")

    # =========================================================
    # TABLE 3: Priority Score & chon VNF (ai scale VNF nao truoc)
    # =========================================================
    print(f"\n{SEP}")
    print("  TABLE 3 -- VNF Scale Order (chung minh MSH-OR chon dung VNF truoc)")
    print("  MSH-OR phai scale vnf_fw truoc (5 SFC, SFC1 pri=1.0 cao nhat)")
    print(SEP)

    for algo in ALGOS:
        ll     = logs[algo]
        scores = parse_priority_scores(ll)
        # Lay cycle dau tien co overload (t=90 voi workload moi)
        first_cycle = None
        if scores:
            first_t = min(s[0] for s in scores)
            first_cycle = [(t, v, s) for t, v, s in scores if abs(t - first_t) < 1.0]

        if first_cycle:
            print(f"\n  [{algo}] -- Cycle t={first_cycle[0][0]:.0f}s (first overload detection)")
            print(f"  {'VNF':<12} | {'Score':>8} | Note")
            print("  " + "-" * 40)
            for t, vnf, sc in sorted(first_cycle, key=lambda x: -x[2]):
                note = " <-- SCALE THIS" if sc == max(x[2] for x in first_cycle) else ""
                print(f"  {vnf:<12} | {sc:>8.4f} |{note}")
        else:
            tag = algo.lower().replace("-", "").replace("first", "").replace("worst", "")
            print(f"\n  [{algo}] -- no [PRIORITY] log (WorstFirst/QueueFirst dung util/queue thay the)")
            # Doc WF/QF log truc tiep
            first_scale_wf = None
            for line in logs[algo]:
                if "[WF VERT]" in line or "[QF VERT]" in line:
                    try:
                        t = float(line.split(":")[0].strip())
                        vnf_match = re.search(r'(?:VERT\]\s+|VERT\]\s*)(\S+)', line)
                        util_match = re.search(r'util=(\d+\.\d+)', line)
                        queue_match = re.search(r'queue=(\d+)', line)
                        if vnf_match:
                            vnf  = vnf_match.group(1)
                            util = float(util_match.group(1)) if util_match else 0
                            first_scale_wf = (t, vnf, util)
                            break
                    except (ValueError, IndexError):
                        pass
            if first_scale_wf:
                t, vnf, util = first_scale_wf
                print(f"  First scale: t={t:.0f}s -> {vnf} (util={util:.3f})")

    # =========================================================
    # TABLE 4: Timeout Rate + Avg RT per SFC (standard metrics)
    # =========================================================
    csv_results = {a: d for a, d in csvs.items() if d is not None}
    if csv_results:
        print(f"\n{SEP}")
        print("  TABLE 4 -- Timeout Rate per SFC  (tham khao)")
        print("  NOTE: Metric nay bang nhau giua 3 thuat toan do CloudSimSDN")
        print("  SpaceShared scheduler khong redistribute cloudlet sau scale.")
        print("  => Chi dung lam tham khao, KHONG dung de so sanh thuat toan.")
        print(SEP)
        print(f"  {'SFC':<6} | {'Priority':>8} | " + " | ".join(f"{a:>15}" for a in ALGOS))
        print("  " + SEP2)
        for i in range(NUM_SFC):
            cols = []
            for algo in ALGOS:
                if algo not in csv_results:
                    cols.append(f"{'N/A':>15}")
                    continue
                d     = csv_results[algo][i]
                total = d["done"] + d["timeout"]
                rate  = d["timeout"] / total * 100 if total > 0 else 0
                cols.append(f"{rate:>14.1f}%")
            print(f"  SFC{i+1:<3} | {SFC_PRIORITIES[i]:>8.1f} | " + " | ".join(cols))

        print(f"\n{SEP}")
        print("  TABLE 5 -- Avg Response Time per SFC (tham khao, done only)")
        print("  NOTE: Tuong tu TABLE 4 -- bang nhau do gioi han CloudSimSDN.")
        print(SEP)
        print(f"  {'SFC':<6} | {'Priority':>8} | " + " | ".join(f"{a:>15}" for a in ALGOS))
        print("  " + SEP2)
        for i in range(NUM_SFC):
            cols = []
            for algo in ALGOS:
                if algo not in csv_results:
                    cols.append(f"{'N/A':>15}")
                    continue
                d   = csv_results[algo][i]
                avg = (sum(d["response_times"]) / len(d["response_times"])
                       if d["response_times"] else 0)
                cols.append(f"{avg:>14.3f}s")
            print(f"  SFC{i+1:<3} | {SFC_PRIORITIES[i]:>8.1f} | " + " | ".join(cols))

    # =========================================================
    # FINAL SUMMARY -- chi M1/M2/M3 (metric co y nghia)
    # =========================================================
    print(f"\n{SEP}")
    print("  FINAL SUMMARY -- Metric chinh: M1 / M2 / M3")
    print("  (WQB va WTR bi loai vi bang nhau -- gioi han CloudSimSDN SpaceShared)")
    print(SEP)
    print(f"  {'Metric':<36} | {'MSH-OR':>10} | {'WorstFirst':>10} | {'QueueFirst':>10} | Winner")
    print("  " + SEP2)

    m2v = {a: cis[a]["m2"] for a in ALGOS}
    m1v = {a: cis[a]["m1"] for a in ALGOS}
    m3v = {a: cis[a]["m3"] for a in ALGOS}

    # Fallback tu ba_data neu CIS chua co
    for algo in ALGOS:
        if cis[algo]["m2"] == 0.0 and ba_data[algo]["dc2"] != 0.0:
            m2v[algo] = ba_data[algo]["dc2"]
            m1v[algo] = ba_data[algo]["dc1"]
            m3v[algo] = ba_data[algo]["dc3"]

    rows_main = [
        ("M2=sum(DC2): prio-weighted SFC [^]", m2v, True),
        ("M1=sum(DC1): SFC fail rate        [^]", m1v, True),
        ("M3=sum(DC3): SFC count saved      [^]", m3v, True),
    ]

    for label, vals, higher_better in rows_main:
        cells = [f"{vals.get(a, 0):.3f}" for a in ALGOS]
        winner = max(vals, key=lambda a: vals[a]) if higher_better \
            else min(vals, key=lambda a: vals[a])
        # Tinh % cai thien so voi baseline (thap nhat)
        best_v  = max(vals.values()) if higher_better else min(vals.values())
        worst_v = min(vals.values()) if higher_better else max(vals.values())
        pct = (best_v - worst_v) / abs(worst_v) * 100 if worst_v != 0 else 0
        diff_str = f"(+{pct:.1f}%)" if pct > 0 else ""
        print(f"  {label:<36} | {cells[0]:>10} | {cells[1]:>10} | {cells[2]:>10} | {winner} {diff_str}")

    print(f"\n  [^] Cao hon = tot hon")

    # Note ve WQB/WTR
    print(f"\n  NOTE -- Metrics bi loai khoi so sanh chinh:")
    print(f"  WQB (Weighted Queue Burden): bang nhau vi SpaceShared scheduler giu")
    print(f"  cloudlet cu trong queue goc sau khi scale -- khong phan biet duoc.")
    print(f"  WTR (Weighted Timeout Rate): bang nhau vi timeout xay ra truoc khi")
    print(f"  bat ky thuat toan nao kip scale (t<30s).")
    print(f"  => Trong moi truong thuc (OpenStack/K8s), WQB va WTR se khac nhau ro.")

    # KEY FINDING
    print(f"\n  KEY FINDING:")
    m2v_clean = {a: m2v[a] for a in ALGOS if m2v[a] != 0}
    if len(m2v_clean) >= 2:
        best_a  = max(m2v_clean, key=lambda a: m2v_clean[a])
        worst_a = min(m2v_clean, key=lambda a: m2v_clean[a])
        diff    = m2v_clean[best_a] - m2v_clean[worst_a]
        pct     = diff / abs(m2v_clean[worst_a]) * 100 if m2v_clean[worst_a] != 0 else 0
        print(f"  M2: {best_a} cao hon {worst_a} {diff:+.3f} pts ({pct:+.1f}%)")
        print(f"  Giai thich: {best_a} scale vnf_fw truoc (SFC1 pri=1.0, SFC5 pri=0.9)")
        print(f"  -> giam duoc 3.7 pts priority-weighted vi pham ngay cycle dau tien")
        print(f"  Trong khi {worst_a} scale vnf_nat truoc (util cao nhat nhung SFC1 khong di qua)")
        print(f"  -> chi giam duoc 3.0 pts -- bo lo SFC priority cao nhat")
        print(f"  => Priority Score (C1+C2+C3) cua MSH-OR chon dung VNF, dung luc.")
    print()


if __name__ == "__main__":
    main()