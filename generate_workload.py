lines = ["atime,name.1,zeros,w.1.1,link.1.2,name.2,p.1.2,w.2.1,link.2.3,name.3,p.2.3,w.3"]

# 15 requests/giây × 100 MI / 2000 MIPS = 0.75s CPU/giây = 75% utilization
for i in range(40):
    t = round(1.0 + i * 1.0, 1)
    for _ in range(15):
        lines.append(f"{t},client_vm,0,100,default,vnf_fw,1000,100,,,,")

with open("example-sfc/fat-tree-k10-workload.csv", "w") as f:
    f.write("\n".join(lines))

print(f"Generated {len(lines)-1} workload entries")