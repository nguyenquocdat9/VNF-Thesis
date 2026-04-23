import os

os.makedirs("example-sfc", exist_ok=True)

lines = ["atime,name.1,zeros,w.1.1,link.1.2,name.2,p.1.2,w.2.1,link.2.3,name.3,p.2.3,w.3,link.3.4,name.4,p.3.4,w.4"]

# 60 giay, 4 SFC deu nhau, moi SFC 10 req/s
# Cloudlet length = 200 MI (tang tu 100 len 200 de VNF overload ro hon)
for i in range(40):
    t = round(1.0 + i * 1.0, 1)

    # SFC1: client1 -> vnf_fw -> vnf_ids -> vnf_lb (12 req, tang tu 10)
    for _ in range(10):
        lines.append(f"{t},client1,0,100,l_c1_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_lb,vnf_lb,1000,100")

    # SFC2: client2 -> vnf_fw -> vnf_nat -> vnf_ids (12 req, tang tu 10)
    for _ in range(10):
        lines.append(f"{t},client2,0,100,l_c2_fw,vnf_fw,1000,100,l_fw_nat,vnf_nat,1000,100,l_nat_ids,vnf_ids,1000,100")

    # SFC3: client3 -> vnf_fw -> vnf_lb -> vnf_nat (12 req, tang tu 10)
    for _ in range(10):
        lines.append(f"{t},client3,0,100,l_c3_fw,vnf_fw,1000,100,l_fw_lb,vnf_lb,1000,100,l_lb_nat,vnf_nat,1000,100")

    # SFC4: client4 -> vnf_fw -> vnf_ids -> vnf_nat (12 req, tang tu 10)
    for _ in range(10):
        lines.append(f"{t},client4,0,100,l_c4_fw,vnf_fw,1000,100,l_fw_ids,vnf_ids,1000,100,l_ids_nat,vnf_nat,1000,100")

with open("example-sfc/fat-tree-k10-workload.csv", "w") as f:
    f.write("\n".join(lines))

print(f"Generated {len(lines)-1} workload entries")
print("Traffic: 4 SFC x 10 req/s = 40 req/s total")
print("Cloudlet length: 200 MI (increased from 100)")
print("Duration: 60 seconds (0.5 to 60.5)")