package com.thesis.nfv.core;

import com.thesis.nfv.model.PhysicalEdge;
import com.thesis.nfv.model.PhysicalNode;
import java.util.ArrayList;
import java.util.List;

public class NetworkTopology {
    public List<PhysicalNode> allNodes = new ArrayList<>();
    public List<PhysicalEdge> allEdges = new ArrayList<>();

    // Hàm xây dựng Fat-Tree tổng quát với tham số k
    public void buildFatTree(int k) {
        if (k % 2 != 0) {
            throw new IllegalArgumentException("Tham số k của Fat-Tree phải là số chẵn!");
        }

        allNodes.clear();
        allEdges.clear();

        // 1. Khởi tạo Core Nodes: (k/2)^2 nút
        int numCore = (k / 2) * (k / 2);
        List<PhysicalNode> coreNodes = new ArrayList<>();
        for (int i = 0; i < numCore; i++) {
            PhysicalNode core = new PhysicalNode("Core_" + i, 100, 256); // Core thường có tài nguyên lớn
            allNodes.add(core);
            coreNodes.add(core);
        }

        // 2. Duyệt qua từng Pod (tổng cộng k Pods)
        for (int p = 0; p < k; p++) {
            List<PhysicalNode> podAggNodes = new ArrayList<>();
            List<PhysicalNode> podEdgeNodes = new ArrayList<>();

            // Khởi tạo Aggregation và Edge Nodes cho mỗi Pod (mỗi loại k/2 nút)
            for (int i = 0; i < k / 2; i++) {
                PhysicalNode agg = new PhysicalNode("Pod" + p + "_Agg_" + i, 40, 64);
                PhysicalNode edge = new PhysicalNode("Pod" + p + "_Edge_" + i, 50, 64);

                allNodes.add(agg);
                allNodes.add(edge);
                podAggNodes.add(agg);
                podEdgeNodes.add(edge);
            }

            // A. Kết nối nội bộ Pod (Edge <-> Agg): Mesh hoàn chỉnh trong Pod
            // Trễ Edge-Agg: 2ms
            for (PhysicalNode e : podEdgeNodes) {
                for (PhysicalNode a : podAggNodes) {
                    allEdges.add(new PhysicalEdge(e.id, a.id, 10000, 2.0));
                }
            }

            // B. Kết nối Agg <-> Core
            // Mỗi Agg node thứ 'i' sẽ kết nối với k/2 Core nodes
            for (int i = 0; i < podAggNodes.size(); i++) {
                PhysicalNode agg = podAggNodes.get(i);
                // Công thức Fat-Tree: Agg node i kết nối tới các Core node từ (i * k/2) đến (i * k/2 + k/2 - 1)
                int startCoreIndex = i * (k / 2);
                for (int j = 0; j < k / 2; j++) {
                    PhysicalNode core = coreNodes.get(startCoreIndex + j);
                    allEdges.add(new PhysicalEdge(agg.id, core.id, 10000, 4.0));
                }
            }
        }
        System.out.println("[INFO] Topology Fat-Tree k=" + k + " created. Total Nodes: " + allNodes.size());
    }
}