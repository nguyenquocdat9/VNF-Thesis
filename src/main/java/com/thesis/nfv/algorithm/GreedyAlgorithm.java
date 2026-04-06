package com.thesis.nfv.algorithm;

import com.thesis.nfv.model.*;
import java.util.*;

public class GreedyAlgorithm {

    public void runMigration(List<VNFInstance> vnfToMigrate, List<PhysicalNode> edgeNodes) {
        System.out.println("Running greedy algorithm");

        for (VNFInstance vnf : vnfToMigrate) {
            PhysicalNode chosenNode = null;

            for (PhysicalNode node : edgeNodes) {
                // Ràng buộc 1: Không phải nút đang lỗi và không phải nút cũ
                if (node.isFailed || node.id.equals(vnf.hostNode.id)) continue;

                // Ràng buộc 2: Đủ tài nguyên (Greedy thường dùng ngưỡng cứng 100% hoặc 90%)
                if (node.canAccommodate(vnf.cpuReq, vnf.memReq, 0.9)) {
                    chosenNode = node;
                    break; // Thấy nút đầu tiên là chọn luôn, không tối ưu thêm
                }
            }

            if (chosenNode != null) {
                executePhysicalMigration(vnf, chosenNode);
            } else {
                System.out.println("Greedy: Can't find node for VNF " + vnf.id);
            }
        }
    }

    private void executePhysicalMigration(VNFInstance vnf, PhysicalNode target) {
        System.out.println("Greedy: migrate " + vnf.id + " -> " + target.id);
        // Cập nhật tài nguyên
        vnf.hostNode.cpuUsed -= vnf.cpuReq;
        vnf.hostNode.memUsed -= vnf.memReq;

        vnf.hostNode = target;
        target.cpuUsed += vnf.cpuReq;
        target.memUsed += vnf.memReq;
    }
}