package com.thesis.nfv.algorithm;

import com.thesis.nfv.model.*;
import com.thesis.nfv.core.MetricsCalculator;
import java.util.*;

public class MSHORAlgorithm {
    private MetricsCalculator metrics = new MetricsCalculator();

    public void runMigration(List<VNFInstance> vnfToMigrate, List<PhysicalNode> edgeNodes) {
        // --- GIAI ĐOẠN 1: XÁC ĐỊNH THỨ TỰ DI TRÚ TỐI ƯU ---

        // Sắp xếp danh sách VNF cần di trú theo số lượng SFC dùng chung (giảm dần)
        vnfToMigrate.sort((v1, v2) -> {
            int priority1 = v1.sharedBySFCs.size();
            int priority2 = v2.sharedBySFCs.size();
            return Integer.compare(priority2, priority1); // Ưu tiên thằng lớn hơn đứng trước
        });

        System.out.println("Migration Priority (VNFI Sharing):");
        for (VNFInstance vnf : vnfToMigrate) {
            System.out.println("- VNF: " + vnf.id + " | Priority: " + vnf.sharedBySFCs.size());
        }

        // Sau khi có thứ tự, ta sẽ lặp qua từng VNF để thực hiện Giai đoạn 2 và 3
        for (VNFInstance vnf : vnfToMigrate) {
            migrateSingleVNF(vnf, edgeNodes);
        }
    }

    private void migrateSingleVNF(VNFInstance vnf, List<PhysicalNode> allEdgeNodes) {
        double beta = 0.8; // Ngưỡng quá tải 80%
        PhysicalNode bestNode = null;
        double minObjective = Double.MAX_VALUE;

        // GIAI ĐOẠN 2: LỌC DANH SÁCH NÚT ỨNG VIÊN (CANDIDATE NODES)
        List<PhysicalNode> candidates = new ArrayList<>();
        for (PhysicalNode node : allEdgeNodes) {
            // Không di trú ngược lại nút cũ hoặc nút đang lỗi
            if (node.id.equals(vnf.hostNode.id) || node.isFailed) continue;

            // Kiểm tra ràng buộc tài nguyên
            if (node.canAccommodate(vnf.cpuReq, vnf.memReq, beta)) {
                candidates.add(node);
            }
        }

        // GIAI ĐOẠN 3: LỰA CHỌN VỊ TRÍ ĐẶT TỐI ƯU (SELECTION)
        for (PhysicalNode candidate : candidates) {
            // 1. Giả lập di trú: Lưu vị trí cũ, chuyển sang vị trí mới
            PhysicalNode oldNode = vnf.hostNode;
            vnf.hostNode = candidate;
            candidate.cpuUsed += vnf.cpuReq;
            candidate.memUsed += vnf.memReq;

            // 2. Tính toán biến thiên Delta D và Delta L
            // Trong thực tế, ta tính tổng Delay và Load sau khi đặt
            double currentDelay = 0;
            for (SFCRequest sfc : vnf.sharedBySFCs) {
                currentDelay += metrics.calculateTotalSFCDelay(sfc);
            }
            // Giả sử ta lấy Network Load hiện tại làm chỉ số L
            double currentLoad = metrics.calculateNetworkLoad(allEdgeNodes, new ArrayList<>());

            // 3. Tính hàm mục tiêu O(t) = a1*D + a2*L (Cần Normalize nếu cần)
            double objective = metrics.calculateObjectiveValue(currentDelay, currentLoad, 0.5, 0.5);

            if (objective < minObjective) {
                minObjective = objective;
                bestNode = candidate;
            }

            // 4. Hoàn tác giả lập để thử nút tiếp theo
            candidate.cpuUsed -= vnf.cpuReq;
            candidate.memUsed -= vnf.memReq;
            vnf.hostNode = oldNode;
        }

        // Migrate
        if (bestNode != null) {
            System.out.println("Migrate VNF " + vnf.id + " from " + vnf.hostNode.id + " to " + bestNode.id);
            vnf.hostNode.cpuUsed -= vnf.cpuReq; // Release old node
            vnf.hostNode.memUsed -= vnf.memReq;

            vnf.hostNode = bestNode; // Update new node
            bestNode.cpuUsed += vnf.cpuReq;
            bestNode.memUsed += vnf.memReq;
        } else {
            System.out.println("Can't find node for VNF " + vnf.id);
        }
    }
}