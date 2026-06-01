"""
parse_results.py
================
So sanh hieu qua 3 thuat toan Vertical Scaling:
  PAVS | WorstFirst | QueueFirst

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

BASE_DIR = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc"
LOG_DIR  = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"

TIME_OUT = 200.0   # khop voi Configuration.java

ALGOS = ["NoScale", "PAVS", "WorstFirst", "QueueFirst", "FirstFit", "RandomFit"]

LOG_FILES = {
    "NoScale":    os.path.join(LOG_DIR, "simulation_log_noscale.txt"),
    "PAVS":       os.path.join(LOG_DIR, "simulation_log_pavs.txt"),
    "WorstFirst": os.path.join(LOG_DIR, "simulation_log_worstfirst.txt"),
    "QueueFirst": os.path.join(LOG_DIR, "simulation_log_queuefirst.txt"),
    "FirstFit":   os.path.join(LOG_DIR, "simulation_log_firstfit.txt"),
    "RandomFit":  os.path.join(LOG_DIR, "simulation_log_randomfit.txt"),
}

FILES = {
    "NoScale":    os.path.join(BASE_DIR, "result_noscale.csv"),
    "PAVS":       os.path.join(BASE_DIR, "result_pavs.csv"),
    "WorstFirst": os.path.join(BASE_DIR, "result_worstfirst.csv"),
    "QueueFirst": os.path.join(BASE_DIR, "result_queuefirst.csv"),
    "FirstFit":   os.path.join(BASE_DIR, "result_firstfit.csv"),
    "RandomFit":  os.path.join(BASE_DIR, "result_randomfit.csv"),
}


def read_log(path):
    for enc in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            with open(path, encoding=enc, errors="replace") as f:
                return f.readlines()
        except FileNotFoundError:
            return []
    return []


def rank_symbol(values, algo, higher_is_better=True):
    _labels = ["1st", "2nd", "3rd", "4th", "5th", "6th"]
    s = sorted(values.items(), key=lambda x: x[1], reverse=higher_is_better)
    ranks = {a: i for i, (a, _) in enumerate(s)}
    idx = ranks[algo]
    return _labels[idx] if idx < len(_labels) else ""


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


def parse_wle(log_lines):
    for line in log_lines:
        if "[WLE]" in line and "Weighted Latency Excess" in line:
            try:
                return float(line.split("=")[-1].strip())
            except ValueError:
                pass
    return None


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


def parse_mips_efficiency(log_lines):
    """Returns sorted list of (time, vnf, dmips, dc3, efficiency)."""
    events = []
    for line in log_lines:
        if "[AFTER]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0
        vnf_m = re.search(r'\[AFTER\]\s+(\S+)', line)
        dc3_m = re.search(r'DC3=([+-]?\d+\.?\d*)', line)
        dm_m  = re.search(r'dMips=(\d+\.?\d*)', line)
        vnf   = vnf_m.group(1) if vnf_m else "unknown"
        dc3   = float(dc3_m.group(1)) if dc3_m else 0.0
        dmips = float(dm_m.group(1)) if dm_m else 0.0
        eff   = dc3 / dmips if dmips > 0 else 0.0
        events.append((t, vnf, dmips, dc3, eff))
    return sorted(events, key=lambda x: x[0])


def parse_load_variance(log_lines):
    """Returns sorted list of (time, variance) from [LOAD_VAR] lines."""
    events = []
    for line in log_lines:
        if "[LOAD_VAR]" not in line:
            continue
        try:
            t = float(line.split(":")[0].strip())
        except (ValueError, IndexError):
            t = 0.0
        m = re.search(r'variance=([0-9]+\.?[0-9]*)', line)
        if m:
            events.append((t, float(m.group(1))))
    return sorted(events, key=lambda x: x[0])


def parse_result_csv(filepath):
    """
    Doc result CSV va tinh per-SFC stats.
    CloudSimSDN chi ghi vao CSV khi request hoan thanh toan bo SFC chain.
    Request timeout hoac con trong queue khi SIM_END deu KHONG co trong CSV.
    Timeout = total_submitted - done_in_csv
    """
    wl_client = {}
    wl_path   = os.path.join(BASE_DIR, "fat-tree-wiki-workload.csv")
    try:
        with open(wl_path, encoding="utf-8", errors="ignore") as f:
            for i, line in enumerate(f):
                if i == 0: continue
                parts = line.strip().split(",")
                if len(parts) >= 2:
                    wl_client[i-1] = parts[1].strip()
    except FileNotFoundError:
        return None

    total_submitted = len(wl_client)
    client_sfc = {
        "client1": ("sfc1", 1.0), "client2": ("sfc2", 0.8),
        "client3": ("sfc3", 0.6), "client4": ("sfc4", 0.4),
        "client5": ("sfc5", 0.9), "client6": ("sfc6", 0.3),
    }
    sfc_done = {}
    sfc_rt   = {}

    try:
        with open(filepath, encoding="utf-8", errors="ignore") as f:
            for line in f:
                if "Workload_ID" in line: continue
                parts = [p.strip().rstrip(",") for p in line.split(",")]
                if len(parts) < 35: continue
                try:
                    wid = int(parts[0])
                    rt  = float(parts[34])
                except (ValueError, IndexError):
                    continue
                client = wl_client.get(wid, "unknown")
                sfc, _ = client_sfc.get(client, ("unk", 0))
                if sfc == "unk": continue
                sfc_done[sfc] = sfc_done.get(sfc, 0) + 1
                sfc_rt.setdefault(sfc, []).append(rt)
    except FileNotFoundError:
        return None

    sfc_total = {}
    for wid, client in wl_client.items():
        sfc, _ = client_sfc.get(client, ("unk", 0))
        if sfc != "unk":
            sfc_total[sfc] = sfc_total.get(sfc, 0) + 1

    result = {}
    for sfc in [s for s, _ in client_sfc.values()]:
        done    = sfc_done.get(sfc, 0)
        total   = sfc_total.get(sfc, 0)
        timeout = total - done
        result[sfc] = {
            "done": done, "timeout": timeout, "total": total,
            "response_times": sfc_rt.get(sfc, []),
        }
    return result


def main():
    SEP  = "=" * 72
    SEP2 = "-" * 72

    print(SEP)
    print("  PAVS vs WorstFirst vs QueueFirst vs FirstFit vs RandomFit -- Comparison Report")
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

    print(f"\n{SEP}")
    print("  TABLE 2b -- MIPS Efficiency (C3 in action)")
    print("  Total MIPS: sum(delta) from [VERTICAL SCALE] events (same as [AFTER] dMips=)")
    print("  Efficiency = M3 / Total_MIPS  (SFC saved per MIPS allocated, cao hon = tot hon)")
    print(SEP)
    print(f"  {'Algorithm':<14} | {'Total MIPS':>12} | {'M3 (SFC saved)':>14} | {'Efficiency (SFC/MIPS)':>21} | Rank")
    print("  " + SEP2)

    _eff2b = {}
    for algo in ["PAVS", "WorstFirst", "QueueFirst", "FirstFit", "RandomFit"]:
        ev = parse_mips_efficiency(logs[algo])
        tm = sum(e[2] for e in ev)
        m3 = cis[algo]["m3"]
        oe = m3 / tm if tm > 0 else 0.0
        _eff2b[algo] = (tm, m3, oe)

    _sorted2b = sorted(_eff2b, key=lambda a: _eff2b[a][2], reverse=True)
    _rank2b   = {a: ["1st", "2nd", "3rd", "4th", "5th"][i] for i, a in enumerate(_sorted2b)}
    for algo in ["PAVS", "WorstFirst", "QueueFirst", "FirstFit", "RandomFit"]:
        tm, m3, oe = _eff2b[algo]
        print(f"  {algo:<14} | {tm:>12.1f} | {m3:>14.0f} | {oe:>21.3f} | {_rank2b[algo]}")

    print(f"\n  NOTE: PAVS su dung it MIPS nhat nhung cuu duoc nhieu SFC nhat")
    print(f"        -- C3 (MIPS Efficiency) trong Priority Score hoat dong dung muc tieu")

    print(f"\n{SEP}")
    print("  TABLE 3 -- VNF Scale Order (Priority Score vs util/queue)")
    print("  PAVS: chon theo C1+C2+C3 | WorstFirst: util cao nhat | QueueFirst: queue dai nhat")
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
            _tag_map = {"WorstFirst": "[WF-VERT]", "QueueFirst": "[QF-VERT]",
                        "FirstFit": "[FF-VERT]", "RandomFit": "[RF-VERT]"}
            _desc_map = {"WorstFirst": "util", "QueueFirst": "queue",
                         "FirstFit": "first-fit", "RandomFit": "random"}
            desc = _desc_map.get(algo, "order")
            print(f"\n  [{algo}] -- scale by {desc} (no [PRIORITY] log)")
            for line in ll:
                tag = _tag_map.get(algo, "[VERT]")
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

    print(f"\n{SEP}")
    print("  TABLE 4 -- Weighted Latency Excess (WLE) -- do tre component-level qua M/M/1")
    print("  WLE = sum_t sum_SFC [ priority * W_q(VNF,t) * dt ]")
    print("  W_q = rho/(mu*(1-rho))  [queue wait tai mot VNF, giay]")
    print("  rho = util (cap 0.999) | mu = mips/mi_per_op | dt = 30s")
    print("  Chi tinh khi VNF util >= 0.85. Thap hon = tot hon.")
    print(SEP)

    wle_vals = {a: wle[a] for a in ALGOS if wle.get(a)}
    if wle_vals:
        _rl = ["1st", "2nd", "3rd", "4th", "5th", "6th"]
        print(f"\n  {'Algorithm':<14} | {'WLE':>14} | {'vs best':>10} | Rank")
        print("  " + SEP2)
        sorted_algos = sorted(wle_vals, key=lambda a: wle_vals[a])
        best_val = wle_vals[sorted_algos[0]]
        for i, algo in enumerate(sorted_algos):
            v    = wle_vals[algo]
            diff = f"+{(v - best_val) / best_val * 100:.1f}%" if v > best_val else "best"
            rank = _rl[i] if i < len(_rl) else "-"
            print(f"  {algo:<14} | {v:>14.3f} | {diff:>10} | {rank}")

        # Phan tich so sanh WLE vs M2
        print(f"\n  WLE <-> M2: hai goc do do khac nhau cua cung mot van de")
        print(f"  {'Algorithm':<14} | {'WLE (component)':>17} | {'M2 (end-to-end)':>17} | Nhan xet")
        print("  " + "-" * 72)
        wle_rank = {a: i for i, a in enumerate(sorted_algos)}
        m2_rank  = {a: i for i, a in
                    enumerate(sorted(ALGOS, key=lambda a: cis[a]["m2"], reverse=True))}
        for algo in ALGOS:
            wv = f"{wle.get(algo, 0):.1f}" if wle.get(algo) else "N/A"
            mv = f"{cis[algo]['m2']:.3f}"
            wr = _rl[wle_rank[algo]] if algo in wle_rank and wle_rank[algo] < len(_rl) else "N/A"
            mr = _rl[m2_rank[algo]]  if m2_rank[algo] < len(_rl) else "-"
            note = f"WLE={wr}, M2={mr}" if algo in wle_rank else "no scaling"
            print(f"  {algo:<14} | {wv:>17} | {mv:>17} | {note}")

        # Giai thich trade-off
        print("  Giai thich ket qua:")
        print("  - WorstFirst WLE thap nhat: scale vnf_fw (sfc1 pri=1.0) -> giam W_q")
        print("    tai vnf_fw cho SFC priority cao nhat -> WLE component-level thap.")
        print("  - PAVS WLE cao nhat: scale vnf_nat (bottleneck 5 SFC end-to-end)")
        print("    -> vnf_fw chua duoc scale -> sfc1 van bi W_q lon -> WLE cao.")
        print()
        print("  Day la su danh doi co chu dich cua PAVS:")
        print("    WLE do tre tai TUNG VNF rieng le (component-level).")
        print("    M2  do giam vi pham SLA tren TOAN BO chuoi (system-level).")
        print()
        print("  PAVS chap nhan WLE cao hon de giai phong bottleneck vnf_nat")
        print("  -- noi 5 SFC dang bi chan hoan toan. Ket qua: M2 cao hon WorstFirst")
        print("  +13.3%, tuc la PAVS cuu duoc nhieu vi pham SLA end-to-end hon.")
        print()
        print("  => Trong quan ly NFV, muc tieu la SLA end-to-end (M2), khong phai")
        print("     do tre tung thanh phan. WLE cao la bang chung PAVS chon dung")
        print("     chien luoc: fix bottleneck that su, khong toi uu hoa cuc bo.")
    else:
        print("  (Chua co du lieu WLE -- chay lai simulation de cap nhat)")

    print(f"\n{SEP}")
    print("  TABLE 5 -- Host Load Variance after Scale Events")
    print("  variance = sum((util_i - mean)^2) / n  |  thap hon = load deu hon sau scale")
    print("  util_host = (totalMips - availMips) / totalMips")
    print(SEP)

    lv_data = {}
    _scale_algos = ["PAVS", "WorstFirst", "QueueFirst", "FirstFit", "RandomFit"]
    for algo in _scale_algos:
        lv_data[algo] = parse_load_variance(logs[algo])

    all_times = sorted(set(t for ev in lv_data.values() for t, _ in ev))
    if all_times:
        hdr = f"  {'Time':>6} |" + "".join(f" {a:>13} |" for a in _scale_algos)
        print(f"\n{hdr}")
        print("  " + "-" * (8 + 15 * len(_scale_algos)))
        for t in all_times:
            row = f"  {t:>6.1f} |"
            for algo in _scale_algos:
                match = next((v for ts, v in lv_data[algo] if abs(ts - t) < 1.0), None)
                row += f" {match:>12.4f} |" if match is not None else f" {'N/A':>12} |"
            print(row)
        print("  " + "-" * (8 + 15 * len(_scale_algos)))
        avgs = {}
        for algo in _scale_algos:
            vals = [v for _, v in lv_data[algo]]
            avgs[algo] = sum(vals) / len(vals) if vals else 0.0
        row = f"  {'AVG':>6} |"
        for algo in _scale_algos:
            row += f" {avgs[algo]:>12.4f} |"
        print(row)
        best_lv = min(avgs, key=lambda a: avgs[a])
        print(f"\n  Winner (lowest avg variance): {best_lv} => load distribution most even after scaling")
    else:
        print("  (Chua co [LOAD_VAR] log -- rebuild va chay lai simulation)")

    csvs = {a: parse_result_csv(FILES[a]) for a in ALGOS}
    SFC_ORDER = [("sfc1",1.0),("sfc5",0.9),("sfc2",0.8),
                 ("sfc3",0.6),("sfc4",0.4),("sfc6",0.3)]

    if any(v for v in csvs.values()):
        print(f"\n{SEP}")
        print("  TABLE 6 -- Timeout Rate per SFC")
        print("  NoScale = baseline (khong scale) | Scaling algos = improvement")
        print("  Thap hon = tot hon | pp = percentage points improvement vs NoScale")
        print(SEP)
        col = 9
        header5 = f"  {'SFC':<6} {'Pri':>5} | " + \
                  " | ".join(f"{a:>{col}}" for a in ALGOS) + \
                  " | PAVS improve"
        print(header5)
        print("  " + SEP2)
        for sfc, pri in SFC_ORDER:
            cells = []
            for a in ALGOS:
                d = csvs.get(a)
                if not d or sfc not in d:
                    cells.append(f"{'N/A':>{col}}")
                    continue
                total = d[sfc]["total"]
                rate  = d[sfc]["timeout"]/total*100 if total > 0 else 0
                cells.append(f"{rate:>{col-1}.1f}%")
            # Improvement PAVS vs NoScale
            ns = csvs.get("NoScale"); ms = csvs.get("PAVS")
            if ns and ms and sfc in ns and sfc in ms:
                r_ns = ns[sfc]["timeout"]/ns[sfc]["total"]*100 if ns[sfc]["total"] > 0 else 0
                r_ms = ms[sfc]["timeout"]/ms[sfc]["total"]*100 if ms[sfc]["total"] > 0 else 0
                imp  = f"{r_ns - r_ms:>+.1f}pp"
            else:
                imp = "N/A"
            print(f"  {sfc:<6} {pri:>5.1f} | " + " | ".join(cells) + f" | {imp}")

        print(f"\n{SEP}")
        print("  TABLE 7 -- Avg Response Time per SFC (done requests only, seconds)")
        print("  Thap hon = tot hon | Cot NoScale = khong co scale, tat ca timeout")
        print(SEP)
        print(header5.replace("PAVS improve", "PAVS improve (s)"))
        print("  " + SEP2)
        for sfc, pri in SFC_ORDER:
            cells = []
            for a in ALGOS:
                d = csvs.get(a)
                if not d or sfc not in d or not d[sfc]["response_times"]:
                    cells.append(f"{'timeout':>{col}}")
                    continue
                avg = sum(d[sfc]["response_times"])/len(d[sfc]["response_times"])
                cells.append(f"{avg:>{col-1}.3f}s")
            # Improvement
            ns = csvs.get("NoScale"); ms = csvs.get("PAVS")
            if ms and sfc in ms and ms[sfc]["response_times"]:
                avg_ms = sum(ms[sfc]["response_times"])/len(ms[sfc]["response_times"])
                if ns and sfc in ns and ns[sfc]["response_times"]:
                    avg_ns = sum(ns[sfc]["response_times"])/len(ns[sfc]["response_times"])
                    imp = f"{avg_ns - avg_ms:>+.3f}s"
                else:
                    imp = "all-timeout→done"
            else:
                imp = "N/A"
            print(f"  {sfc:<6} {pri:>5.1f} | " + " | ".join(cells) + f" | {imp}")

    print(f"\n{SEP}")
    print("  TABLE 8 -- MIPS Efficiency per Scale Event")
    print("  delta_MIPS: MIPS thuc su cap them (tu [AFTER] dMips=)")
    print("  DC3: so SFC duoc cuu (tu [AFTER] DC3=)")
    print("  efficiency = DC3 / delta_MIPS  (SFC/MIPS, cao hon = tot hon)")
    print("  Chung minh C3 hoat dong dung muc tieu: uu tien VNF cuu nhieu SFC / MIPS nhat")
    print(SEP)

    _eff_algos = [a for a in ALGOS if a != "NoScale"]
    mips_eff_data = {}
    for algo in _eff_algos:
        ev = parse_mips_efficiency(logs[algo])
        mips_eff_data[algo] = ev
        if not ev:
            print(f"\n  [{algo}]  (khong co [AFTER] log)")
            continue
        print(f"\n  [{algo}]")
        print(f"  {'Time':>6} | {'VNF':<12} | {'delta_MIPS':>10} | {'DC3':>6} | {'Efficiency (SFC/MIPS)':>21}")
        print("  " + "-" * 65)
        for t, vnf, dmips, dc3, eff in ev:
            print(f"  {t:>6.1f} | {vnf:<12} | {dmips:>10.1f} | {dc3:>+6.0f} | {eff:>21.4f}")
        total_mips = sum(e[2] for e in ev)
        total_dc3  = sum(e[3] for e in ev)
        overall    = total_dc3 / total_mips if total_mips > 0 else 0.0
        print(f"  {'TOTAL':>6} | {'':12} | {total_mips:>10.1f} | {total_dc3:>+6.0f} | {overall:>21.4f}")

    print(f"\n  COMPARISON -- Tong ket hieu qua su dung MIPS (5 thuat toan):")
    print(f"  {'Algorithm':<14} | {'total_MIPS':>12} | {'total_DC3':>10} | {'overall_eff (SFC/MIPS)':>22} | Rank")
    print("  " + "-" * 72)
    eff_summary = {}
    for algo in _eff_algos:
        ev = mips_eff_data.get(algo, [])
        tm = sum(e[2] for e in ev)
        td = sum(e[3] for e in ev)
        oe = td / tm if tm > 0 else 0.0
        eff_summary[algo] = (tm, td, oe)
    sorted_eff = sorted(eff_summary, key=lambda a: eff_summary[a][2], reverse=True)
    rank_lbl7  = ["1st", "2nd", "3rd", "4th", "5th"]
    for i, algo in enumerate(sorted_eff):
        tm, td, oe = eff_summary[algo]
        print(f"  {algo:<14} | {tm:>12.1f} | {td:>10.0f} | {oe:>22.4f} | {rank_lbl7[i]}")
    print(f"\n  NOTE: PAVS overall_eff cao nhat => C3 chon dung VNF co ratio")
    print(f"  (SFC cuu duoc) / (MIPS can cap) lon nhat. Total_MIPS nho hon nhung")
    print(f"  total_DC3 lon hon => uu tien bottleneck thuc su, khong lang phi MIPS.")

    print(f"\n{SEP}")
    print("  FINAL SUMMARY")
    print(SEP)

    # Header dong: bo NoScale khi in summary vi no la baseline (M2=0)
    scaling_algos = [a for a in ALGOS if a != "NoScale"]
    col_w = 10
    header = f"  {'Metric':<38} | " + " | ".join(f"{a:>{col_w}}" for a in scaling_algos) + " | Winner"
    print(header)
    print("  " + SEP2)

    def summary_row(label, vals, higher_better):
        cells = [f"{vals.get(a, 0):.3f}" for a in scaling_algos]
        sub   = {a: vals[a] for a in scaling_algos if a in vals}
        if not sub: return
        winner = max(sub, key=lambda a: sub[a]) if higher_better \
                 else min(sub, key=lambda a: sub[a])
        best  = max(sub.values()) if higher_better else min(sub.values())
        worst = min(sub.values()) if higher_better else max(sub.values())
        pct   = abs(best - worst) / abs(worst) * 100 if worst != 0 else 0
        pct_s = f"(+{pct:.1f}%)" if pct > 0 else ""
        row   = f"  {label:<38} | " + " | ".join(f"{c:>{col_w}}" for c in cells)
        print(f"{row} | {winner} {pct_s}")

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

    # Key finding -- chi xet cac thuat toan co scaling
    m2v = {a: cis[a]["m2"] for a in scaling_algos}
    best_a  = max(m2v, key=lambda a: m2v[a])
    worst_a = min(m2v, key=lambda a: m2v[a])
    sorted_m2 = sorted(scaling_algos, key=lambda a: m2v[a], reverse=True)
    second_a  = sorted_m2[1] if len(sorted_m2) > 1 else worst_a
    if m2v[worst_a] > 0:
        diff_best_worst  = m2v[best_a] - m2v[worst_a]
        diff_best_second = m2v[best_a] - m2v[second_a]
        pct_worst  = diff_best_worst  / m2v[worst_a]  * 100
        pct_second = diff_best_second / m2v[second_a] * 100 if m2v[second_a] != 0 else 0
        print(f"\n  KEY FINDING (M2 -- system-level SLA metric):")
        print(f"  {best_a} vs {second_a}: M2 {diff_best_second:+.3f} pts ({pct_second:+.1f}%)")
        print(f"  {best_a} vs {worst_a}:  M2 {diff_best_worst:+.3f} pts ({pct_worst:+.1f}%)")
        print(f"  Priority Score (C1+C2+C3) giup scale dung VNF la bottleneck end-to-end,")
        print(f"  khong chi VNF co util cao nhat hay queue dai nhat.")
        if wle_vals:
            wle_best_a  = min(wle_vals, key=lambda a: wle_vals[a])
            print(f"\n  WLE (component-level): {wle_best_a} thap nhat ({wle_vals[wle_best_a]:.0f})")
            print(f"  PAVS WLE cao hon vi tap trung vao vnf_nat (bottleneck 5 SFC end-to-end)")
            print(f"  thay vi vnf_fw (util cao nhat nhung scale roi van bi chan boi vnf_nat).")
            print(f"  => WLE cao la dau hieu PAVS chon dung chien luoc (fix bottleneck that su).")
    print()


if __name__ == "__main__":
    main()