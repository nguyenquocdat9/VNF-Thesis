lines = ["atime,name.1,zeros,w.1.1,link.1.2,name.2,p.1.2,w.2.1,link.2.3,name.3,p.2.3,w.3,link.3.4,name.4,p.3.4,w.4"]

for i in range(40):
    t = round(1.0 + i * 1.0, 1)

    # SFC1: vnf_fw -> vnf_ids -> vnf_lb (15 req)
    for _ in range(15):
        lines.append(f"{t},client1,0,100,l_c1_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_lb,vnf_lb,1000,100")

    # SFC2: vnf_fw -> vnf_nat -> vnf_ids (8 req)
    for _ in range(8):
        lines.append(f"{t},client2,0,100,l_c2_fw,vnf_fw,1000,100,l_fw_nat,vnf_nat,1000,100,l_nat_ids,vnf_ids,1000,100")

    # SFC3: vnf_fw -> vnf_lb -> vnf_nat (8 req)
    for _ in range(8):
        lines.append(f"{t},client3,0,100,l_c3_fw,vnf_fw,1000,100,l_fw_lb,vnf_lb,1000,100,l_lb_nat,vnf_nat,1000,100")

    # SFC4: vnf_fw -> vnf_ids -> vnf_nat (7 req)
    for _ in range(7):
        lines.append(f"{t},client4,0,100,l_c4_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100")

with open("example-sfc/fat-tree-k10-workload.csv", "w") as f:
    f.write("\n".join(lines))

print(f"Generated {len(lines)-1} workload entries")
print("Traffic distribution:")
print("  vnf_fw:  40 req/s (all SFCs)")
print("  vnf_nat: 25 req/s (SFC3=20, SFC2=5) -> heavy")
print("  vnf_ids: 15 req/s (SFC1=10, SFC4=5) -> medium")
print("  vnf_lb:  30 req/s (SFC1=10, SFC3=20) -> heavy")