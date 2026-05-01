"""
generate_workload.py
====================
Tạo workload cho CloudSimSDN từ Wikipedia access trace thực tế.

NGUỒN DỮ LIỆU
--------------
Dataset: Wikipedia access trace (Wikibench)
  URL:   http://www.wikibench.eu/?page_id=60
  File:  overallstatpersec9-1-sfc.csv
  Cột:   timestamp(s), language, request_count_per_second

Dataset này được sử dụng trong bài báo gốc của CloudSimSDN:
  Son, J., He, T., Buyya, R. (2019). CloudSimSDN-NFV: Modeling and
  Simulation of NFV and SFC in Edge Computing. SPE. DOI: 10.1002/spe.2755
  Tool: https://github.com/Cloudslab/sfcwikiworkload

PHƯƠNG PHÁP ÁNH XẠ
--------------------
Bước 1: Chọn window 40 giây từ trace có pattern tăng rõ ràng
        → Row 259180-259219 (timestamp 259180s-259219s)
        → t=1-20s:  ~244 req/s (off-peak)
        → t=21-40s: ~606 req/s (peak, tăng 2.48×)

Bước 2: Ánh xạ Wikipedia req/s → CloudSimSDN req/SFC
        Công thức: req_per_sfc(t) = max(1, round(wiki_req(t) × SF / N))
        Trong đó:
          SF = SCALE_FACTOR = 0.08
          N  = NUM_SFC = 6  (SFC1-SFC6)

        Cơ sở chọn SF = 0.06:
          VNF_FW capacity = 2 PEs × (1000 MIPS / 100 mipOper) = 20 req/s
          Muốn off-peak < capacity: 244 × 0.06 = 14.6 → 3 req/SFC × 4 SFC = 12 < 20 ✓
          Muốn peak > capacity:     606 × 0.06 = 36.4 → 9 req/SFC × 4 SFC = 36 > 20 ✓

Bước 3: Mỗi SFC nhận cùng số request (round-robin load balancing)
        SFC1: client1 → VNF_FW → VNF_IDS → VNF_LB  → server1 (pri=1.0)
        SFC2: client2 → VNF_FW → VNF_NAT → VNF_IDS → server2 (pri=0.8)
        SFC3: client3 → VNF_FW → VNF_LB  → VNF_NAT → server3 (pri=0.6)
        SFC4: client4 → VNF_FW → VNF_IDS → VNF_NAT → server4 (pri=0.4)
        SFC5: client5 → VNF_FW → VNF_ENC → VNF_NAT → server5 (pri=0.9)
        SFC6: client6 → VNF_ENC → VNF_IDS → VNF_NAT → server6 (pri=0.3)

KẾT QUẢ KỲ VỌNG
-----------------
  t=1-20s:  3-4 req/SFC → VNF bình thường → không scale
  t=21-40s: 9 req/SFC   → VNF_FW overload → MSH-OR scale
  → Trigger scale khoảng t=25-30s (sau monitoring window 5s)
"""

import os

# =========================================================
# THAM SỐ — CHỈNH TẠI ĐÂY
# =========================================================

INPUT_FILE  = r"C:\Users\Admin\Documents\GitHub\sfcwikiworkload\overallstatpersec9-1-sfc.csv"
OUTPUT_FILE = r"C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn\example-sfc\fat-tree-wiki-workload.csv"

# Window từ Wikipedia trace
# Row 259180-259219: off-peak (~244 req/s) → peak (~606 req/s)
ROW_START = 259140
ROW_END   = 259340  # exclusive, 200 giây

# Scale factor: ánh xạ Wikipedia req/s → CloudSimSDN req/s
# Cơ sở: VNF_FW capacity = 20 req/s, off-peak < capacity, peak > capacity
SCALE_FACTOR = 0.08

# Số SFC (phải khớp với virtual JSON)
NUM_SFC = 6

# CloudSimSDN workload length per hop (MI) — khớp với mipoper trong virtual JSON
CLOUDLET_LENGTH = 100

# =========================================================
# SFC DEFINITIONS — khớp với fat-tree-k10-virtual.json
# =========================================================

SFC_TEMPLATES = [
    # SFC1 (pri=1.0): client1 -> vnf_fw -> vnf_ids -> vnf_lb -> server1
    ("client1", "l_c1_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_lb,vnf_lb,1000,100"),
    # SFC2 (pri=0.8): client2 -> vnf_fw -> vnf_nat -> vnf_ids -> server2
    ("client2", "l_c2_fw,vnf_fw,1000,100,l_fw_nat,vnf_nat,1000,100,l_nat_ids,vnf_ids,1000,100"),
    # SFC3 (pri=0.6): client3 -> vnf_fw -> vnf_lb -> vnf_nat -> server3
    ("client3", "l_c3_fw,vnf_fw,1000,100,l_fw_lb,vnf_lb,1000,100,l_lb_nat,vnf_nat,1000,100"),
    # SFC4 (pri=0.4): client4 -> vnf_fw -> vnf_ids -> vnf_nat -> server4
    ("client4", "l_c4_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100"),
    # SFC5 (pri=0.9): client5 -> vnf_fw -> vnf_enc -> vnf_nat -> server5
    ("client5", "l_c5_fw,vnf_fw,1000,100,l_fw_enc,vnf_enc,1000,100,l_enc_nat,vnf_nat,1000,100"),
    # SFC6 (pri=0.3): client6 -> vnf_enc -> vnf_ids -> vnf_nat -> server6
    ("client6", "l_c6_enc,vnf_enc,1000,100,l_enc_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100"),
]

# =========================================================
# MAIN
# =========================================================

def main():
    # Đọc Wikipedia trace
    raw_data = []
    with open(INPUT_FILE) as f:
        for i, line in enumerate(f):
            if i < ROW_START:
                continue
            if i >= ROW_END:
                break
            parts = line.strip().split(',')
            if len(parts) >= 3:
                raw_data.append(int(parts[2]))

    print("=" * 60)
    print("WORKLOAD GENERATOR — Wikipedia Trace → CloudSimSDN")
    print("=" * 60)
    print(f"Input:  {INPUT_FILE}")
    print(f"Output: {OUTPUT_FILE}")
    print(f"Window: row {ROW_START}-{ROW_END-1} ({len(raw_data)} seconds)")
    print(f"Wikipedia req/s: min={min(raw_data)}, max={max(raw_data)}")
    print(f"Scale factor: {SCALE_FACTOR}")
    print(f"After scaling: {round(min(raw_data)*SCALE_FACTOR)} - {round(max(raw_data)*SCALE_FACTOR)} req/s total")

    # Header
    lines = []
    lines.append(
        "atime,name.1,zeros,w.1.1,link.1.2,name.2,p.1.2,w.2.1,"
        "link.2.3,name.3,p.2.3,w.3,link.3.4,name.4,p.3.4,w.4"
    )

    total_requests = 0
    t = 1.0

    print("\nSample conversion:")
    print(f"{'Time':>6} | {'Wiki req/s':>10} | {'Total scaled':>12} | {'req/SFC':>7} | Note")
    print("-" * 60)

    for i, wiki_req in enumerate(raw_data):
        # Ánh xạ: tổng req/s sau scale, chia đều cho NUM_SFC SFC
        total_scaled = max(NUM_SFC, round(wiki_req * SCALE_FACTOR))
        req_per_sfc  = max(1, total_scaled // NUM_SFC)

        # Ghi workload cho từng SFC
        for client, chain in SFC_TEMPLATES:
            for _ in range(req_per_sfc):
                lines.append(f"{t:.1f},{client},0,{CLOUDLET_LENGTH},{chain}")
            total_requests += req_per_sfc

        # In sample tại các mốc quan trọng
        capacity_total = 20  # VNF_FW: 2 PEs × 10 req/s
        overload = req_per_sfc * NUM_SFC > capacity_total
        if i < 3 or i == 19 or i == 20 or i == 21 or i >= 38:
            note = "<-- OVERLOAD" if overload else ""
            print(f"t={t:4.0f}s | {wiki_req:>10} | {total_scaled:>12} | {req_per_sfc:>7} | {note}")

        t += 1.0

    # Ghi file
    with open(OUTPUT_FILE, "w") as f:
        f.write("\n".join(lines))

    print("-" * 60)
    print(f"\nTotal workload lines: {len(lines)-1}")
    print(f"Total requests:       {total_requests}")
    print(f"Duration:             {len(raw_data)}s (t=1 to t={len(raw_data)})")
    print(f"\nFile saved: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()