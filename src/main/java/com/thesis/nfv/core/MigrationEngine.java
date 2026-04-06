package com.thesis.nfv.core;

import com.thesis.nfv.model.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;

public class MigrationEngine {
    private NetworkTopology topology;
    private MSHORAlgorithm mshor = new MSHORAlgorithm();
    private GreedyAlgorithm greedy = new GreedyAlgorithm();

    public MigrationEngine(NetworkTopology topology) {
        this.topology = topology;
    }

    // Chế độ chạy: 1 - MSH-OR, 2 - Greedy
    public void triggerMigration(List<VNFInstance> affectedVNFs, int strategy) {
        List<PhysicalNode> edgeNodes = topology.allNodes.stream()
                .filter(n -> n.id.contains("Edge"))
                .toList();

        if (strategy == 1) {
            mshor.runMigration(affectedVNFs, edgeNodes);
        } else {
            greedy.runMigration(affectedVNFs, edgeNodes);
        }
    }

    // Hàm hỗ trợ "Thực thi di trú" (Dùng cho MSH-OR giai đoạn 3)
    public static void deployVNF(VNFInstance vnf, PhysicalNode target) {
        if (vnf.hostNode != null) {
            vnf.hostNode.cpuUsed -= vnf.cpuReq;
            vnf.hostNode.memUsed -= vnf.memReq;
        }
        vnf.hostNode = target;
        target.cpuUsed += vnf.cpuReq;
        target.memUsed += vnf.memReq;
    }
}