package com.thesis.nfv.algorithm;

import com.thesis.nfv.core.MigrationEngine;
import com.thesis.nfv.model.*;
import com.thesis.nfv.core.MetricsCalculator;
import java.util.*;

public class MSHORAlgorithm {
    private MetricsCalculator metrics = new MetricsCalculator();

    public void runMigration(List<VNFInstance> vnfToMigrate, List<PhysicalNode> edgeNodes) {
        if (vnfToMigrate == null || edgeNodes == null) return;

        // Giai đoạn 1: Sắp xếp theo mức độ ưu tiên (VNFI Sharing)
        vnfToMigrate.sort((v1, v2) -> {
            int p1 = (v1.sharedBySFCs != null) ? v1.sharedBySFCs.size() : 0;
            int p2 = (v2.sharedBySFCs != null) ? v2.sharedBySFCs.size() : 0;
            return Integer.compare(p2, p1);
        });

        for (VNFInstance vnf : vnfToMigrate) {
            PhysicalNode bestNode = null;
            double minObjective = Double.MAX_VALUE;
            double alpha1 = 0.7;
            double alpha2 = 0.3;
            List<PhysicalNode> candidates = new ArrayList<>();

            // GIAI ĐOẠN 2: TÌM ỨNG VIÊN (CÓ RÀNG BUỘC)
            for (PhysicalNode node : edgeNodes) {
                if (node == null || node.isFailed) continue;

                // Tránh di trú về nút cũ nếu nút đó chưa hỏng (trong trường hợp quá tải)
                if (vnf.hostNode != null && node.id.equals(vnf.hostNode.id)) continue;

                // Khôi phục check tài nguyên với ngưỡng 90% (beta = 0.9)
                if (node.canAccommodate(vnf.cpuReq, vnf.memReq, 0.9)) {
                    candidates.add(node);
                }
            }

            if (candidates.isEmpty()) {
                System.out.println("    Can't find candidate node for " + vnf.id);
                continue;
            }

            // GIAI ĐOẠN 3: LỰA CHỌN TỐI ƯU (TRỌNG SỐ 0.7 - 0.3)
            for (PhysicalNode candidate : candidates) {
                PhysicalNode oldNode = vnf.hostNode;
                vnf.hostNode = candidate;

                // Giả lập cộng tài nguyên để tính Load
                candidate.cpuUsed += vnf.cpuReq;
                candidate.memUsed += vnf.memReq;

                double currentDelay = 0;
                if (vnf.sharedBySFCs != null) {
                    for (SFCRequest sfc : vnf.sharedBySFCs) {
                        currentDelay += metrics.calculateTotalSFCDelay(sfc);
                    }
                }

                // Tính Load (Phương sai) trên toàn bộ danh sách nút Edge
                double currentLoad = metrics.calculateNetworkLoad(edgeNodes, new ArrayList<>());

                // Áp dụng trọng số ưu tiên trễ của thầy hướng dẫn
                double objective = metrics.calculateObjectiveValue(currentDelay, currentLoad, alpha1, alpha2);

                if (objective < minObjective) {
                    minObjective = objective;
                    bestNode = candidate;
                }

                // Hoàn tác giả lập
                candidate.cpuUsed -= vnf.cpuReq;
                candidate.memUsed -= vnf.memReq;
                vnf.hostNode = oldNode;
            }

            // THỰC THI DI TRÚ
            if (bestNode != null) {
                System.out.println("    => [SUCCESS] Migrate " + vnf.id + " -> " + bestNode.id);
                MigrationEngine.deployVNF(vnf, bestNode);
            }
        }
    }
}