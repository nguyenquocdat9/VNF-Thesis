"""
gen_workloads.py
================
Tạo 4 workload CSV với 3-phase profile khác nhau.

Phase boundaries (200 giây):
  Phase 1: t=  1.. 60s  (60s) -- low load
  Phase 2: t= 61..120s  (60s) -- medium load
  Phase 3: t=121..200s  (80s) -- peak load

Levels:
  L1:  3/ 3/ 3 req/SFC -- constant low (no scaling expected)
  L2:  3/ 5/ 8 req/SFC -- moderate ramp
  L3:  3/ 8/12 req/SFC -- reference (tuong duong workload hien tai)
  L4:  3/12/18 req/SFC -- heavy load

Chay:
    python gen_workloads.py
"""

import os

WORKLOAD_DIR    = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc"
CLOUDLET_LENGTH = 100

PHASE1_END = 60
PHASE2_END = 120
TOTAL_TIME = 200

HEADER = (
    "atime,name.1,zeros,w.1.1,link.1.2,name.2,p.1.2,w.2.1,"
    "link.2.3,name.3,p.2.3,w.3,link.3.4,name.4,p.3.4,w.4"
)

# SFC1-SFC6 chains -- khop voi fat-tree-k10-virtual.json
SFC_TEMPLATES = [
    ("client1", "l_c1_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_lb,vnf_lb,1000,100"),
    ("client2", "l_c2_fw,vnf_fw,1000,100,l_fw_nat,vnf_nat,1000,100,l_nat_ids,vnf_ids,1000,100"),
    ("client3", "l_c3_fw,vnf_fw,1000,100,l_fw_lb,vnf_lb,1000,100,l_lb_nat,vnf_nat,1000,100"),
    ("client4", "l_c4_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100"),
    ("client5", "l_c5_fw,vnf_fw,1000,100,l_fw_enc,vnf_enc,1000,100,l_enc_nat,vnf_nat,1000,100"),
    ("client6", "l_c6_enc,vnf_enc,1000,100,l_enc_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100"),
]

LEVELS = {
    "L1": (3,  3,  3),
    "L2": (3,  5,  8),
    "L3": (3,  8, 12),
    "L4": (3, 12, 18),
}


def gen_workload(label, phases):
    p1, p2, p3 = phases
    lines = [HEADER]
    total_req = 0

    for t in range(1, TOTAL_TIME + 1):
        if t <= PHASE1_END:
            req_per_sfc = p1
        elif t <= PHASE2_END:
            req_per_sfc = p2
        else:
            req_per_sfc = p3

        for client, chain in SFC_TEMPLATES:
            for _ in range(req_per_sfc):
                lines.append(f"{t:.1f},{client},0,{CLOUDLET_LENGTH},{chain}")
            total_req += req_per_sfc

    out_path = os.path.join(WORKLOAD_DIR, f"fat-tree-wiki-workload-{label}.csv")
    with open(out_path, "w") as f:
        f.write("\n".join(lines))

    data_lines = len(lines) - 1
    print(f"  {label}  phases={phases}  lines={data_lines:>6}  total_req={total_req:>6}  -> {os.path.basename(out_path)}")
    return out_path


def main():
    print("=" * 66)
    print("gen_workloads.py -- Generating 4 workload levels")
    print("Phase 1: t=1-60s | Phase 2: t=61-120s | Phase 3: t=121-200s")
    print("=" * 66)

    for label, phases in LEVELS.items():
        gen_workload(label, phases)

    print(f"\nDone. Files saved to: {WORKLOAD_DIR}")


if __name__ == "__main__":
    main()
