package com.thesis.nfv.algorithm;

import com.thesis.nfv.core.MigrationEngine;
import com.thesis.nfv.model.*;
import java.util.*;

public class GreedyAlgorithm {
    public void runMigration(List<VNFInstance> vnfToMigrate, List<PhysicalNode> edgeNodes) {
        System.out.println("--- CHẠY THUẬT TOÁN GREEDY (FIRST-FIT) ---");

        for (VNFInstance vnf : vnfToMigrate) {
            PhysicalNode selectedNode = null;

            // Greedy không tính toán phức tạp, chỉ tìm nút đầu tiên thỏa mãn
            for (PhysicalNode node : edgeNodes) {
                if (node == null || node.isFailed) continue;

                // Tránh nút cũ
                if (vnf.hostNode != null && node.id.equals(vnf.hostNode.id)) continue;

                // Kiểm tra tài nguyên (Threshold 1.0 vì Greedy thường chạy tối đa)
                if (node.canAccommodate(vnf.cpuReq, vnf.memReq, 1.0)) {
                    selectedNode = node;
                    break; // Tìm thấy nút đầu tiên là thoát vòng lặp ngay (Greedy)
                }
            }

            if (selectedNode != null) {
                System.out.println("    => [GREEDY] Migrate " + vnf.id + " -> " + selectedNode.id);
                MigrationEngine.deployVNF(vnf, selectedNode);
            } else {
                System.out.println("    [!] Greedy can't find node for " + vnf.id);
            }
        }
    }
}