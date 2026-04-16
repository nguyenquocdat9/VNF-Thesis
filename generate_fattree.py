import json
import os

k = 10
num_pods = k
num_core = (k // 2) ** 2
num_agg_per_pod = k // 2
num_edge_per_pod = k // 2
num_hosts_per_edge = k // 2

nodes = []
links = []

os.makedirs("example-sfc", exist_ok=True)

# Bandwidth theo cap do Fat-Tree (don vi: Kbps)
BW_CORE_AGG  = 100000000   # 100 Gbps
BW_AGG_EDGE  = 10000000    # 10 Gbps
BW_EDGE_HOST = 1000000     # 1 Gbps
BW_HOST      = 1000000     # 1 Gbps (phai >= virtual link BW)

# Core Switches
for i in range(num_core):
    nodes.append({
        "name": f"core-{i}",
        "type": "core",
        "iops": 1000000000,
        "bw": BW_CORE_AGG,
        "upports": 0,
        "downports": k
    })

for pod in range(num_pods):
    # Aggregation Switches
    for agg in range(num_agg_per_pod):
        agg_name = f"agg-{pod}-{agg}"
        nodes.append({
            "name": agg_name,
            "type": "aggregate",
            "iops": 1000000000,
            "bw": BW_AGG_EDGE,
            "upports": k // 2,
            "downports": k // 2
        })
        for j in range(k // 2):
            core_index = agg * (k // 2) + j
            links.append({
                "source": f"core-{core_index}",
                "destination": agg_name,
                "latency": 1.0,
                "bw": BW_CORE_AGG
            })

    # Edge Switches
    for edge in range(num_edge_per_pod):
        edge_name = f"edge-{pod}-{edge}"
        nodes.append({
            "name": edge_name,
            "type": "edge",
            "iops": 1000000000,
            "bw": BW_AGG_EDGE,
            "upports": k // 2,
            "downports": k // 2
        })
        for agg in range(num_agg_per_pod):
            links.append({
                "source": f"agg-{pod}-{agg}",
                "destination": edge_name,
                "latency": 1.0,
                "bw": BW_AGG_EDGE
            })

        # Hosts
        for h in range(num_hosts_per_edge):
            host_name = f"host-p{pod}-e{edge}-h{h}"
            nodes.append({
                "name": host_name,
                "type": "host",
                "pes": 8,
                "mips": 10000,
                "ram": 32768,
                "storage": 1000000,
                "bw": BW_HOST
            })
            links.append({
                "source": edge_name,
                "destination": host_name,
                "latency": 1.0,
                "bw": BW_EDGE_HOST
            })

topology = {"nodes": nodes, "links": links}
with open("example-sfc/fat-tree-k10-physical.json", "w") as f:
    json.dump(topology, f, indent=2)

print(f"Fat-Tree k=10: {len(nodes)} nodes, {len(links)} links")
print(f"BW: Core-Agg={BW_CORE_AGG}, Agg-Edge={BW_AGG_EDGE}, Edge-Host={BW_EDGE_HOST}")