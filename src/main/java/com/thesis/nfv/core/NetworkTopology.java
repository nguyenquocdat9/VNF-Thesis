package com.thesis.nfv.core;

import com.thesis.nfv.model.PhysicalEdge;
import com.thesis.nfv.model.PhysicalNode;

import java.util.ArrayList;
import java.util.List;

public class NetworkTopology {
    private List<PhysicalNode> allNodes = new ArrayList<>();
    private List<PhysicalEdge> allEdges = new ArrayList<>();

    public void buildFatTreeK6() {
        int k = 6;
        // Khởi tạo Core Nodes: (k/2)^2 = 9
        for (int i = 0; i < 9; i++) allNodes.add(new PhysicalNode("Core_" + i, 30, 64));

        // Khởi tạo 6 Pods, mỗi Pod có 3 Agg và 3 Edge nodes
        for (int p = 0; p < k; p++) {
            List<PhysicalNode> aggNodes = new ArrayList<>();
            List<PhysicalNode> edgeNodes = new ArrayList<>();

            for (int i = 0; i < k/2; i++) {
                PhysicalNode agg = new PhysicalNode("Pod" + p + "_Agg_" + i, 20, 32);
                PhysicalNode edge = new PhysicalNode("Pod" + p + "_Edge_" + i, 15, 16);
                allNodes.add(agg); allNodes.add(edge);
                aggNodes.add(agg); edgeNodes.add(edge);
            }

            // Kết nối nội bộ Pod (Edge-Agg): Trễ 2ms [cite: 418]
            for (PhysicalNode e : edgeNodes)
                for (PhysicalNode a : aggNodes)
                    allEdges.add(new PhysicalEdge(e.id, a.id, 1000, 2.0));

            // Kết nối Agg-Core: Trễ 4ms [cite: 418]
            for (int i = 0; i < aggNodes.size(); i++)
                for (int j = 0; j < k/2; j++)
                    allEdges.add(new PhysicalEdge(aggNodes.get(i).id, allNodes.get(i*(k/2)+j).id, 1000, 4.0));
        }
    }
}