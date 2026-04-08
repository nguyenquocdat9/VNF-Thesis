package com.thesis.nfv.core;

import com.thesis.nfv.model.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;

public class MigrationEngine {

    // Đây là hàm "kích hoạt" quy trình di trú
    public static void triggerMigration(String algorithmType, List<VNFInstance> affectedVNFs, List<PhysicalNode> edgeNodes) {
        if (algorithmType.equalsIgnoreCase("MSHOR")) {
            System.out.println("\n[ENGINE] Đang thực thi thuật toán MSH-OR...");
            new MSHORAlgorithm().runMigration(affectedVNFs, edgeNodes);
        } else if (algorithmType.equalsIgnoreCase("GREEDY")) {
            System.out.println("\n[ENGINE] Đang thực thi thuật toán GREEDY (First-Fit)...");
            new GreedyAlgorithm().runMigration(affectedVNFs, edgeNodes);
        }
    }

    // Hàm thực thi đặt VNF (đã dùng trong thuật toán)
    public static void deployVNF(VNFInstance vnf, PhysicalNode targetNode) {
        if (vnf.hostNode != null) {
            vnf.hostNode.cpuUsed -= vnf.cpuReq;
            vnf.hostNode.memUsed -= vnf.memReq;
        }
        vnf.hostNode = targetNode;
        targetNode.cpuUsed += vnf.cpuReq;
        targetNode.memUsed += vnf.memReq;
    }
}