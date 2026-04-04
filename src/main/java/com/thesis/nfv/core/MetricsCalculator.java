package com.thesis.nfv.core;

import com.thesis.nfv.model.PhysicalEdge;
import com.thesis.nfv.model.PhysicalNode;
import com.thesis.nfv.model.SFCRequest;
import com.thesis.nfv.model.VNFInstance;

import java.util.List;

public class MetricsCalculator {
    // Hàm tổng quát tính mục tiêu O(t) = a1*DeltaD + a2*DeltaL [cite: 252]
    public double calculateObjectiveValue(double deltaD, double deltaL, double alpha1, double alpha2) {
        return alpha1 * deltaD + alpha2 * deltaL;
    }

    // 1. Tính tổng trễ của 1 SFC (D_mu = T_mu + P_mu) [cite: 199]
    public double calculateTotalSFCDelay(SFCRequest sfc) {
        double t_mu = calculateTransmissionDelay(sfc); // Dựa trên hops trong k=6
        double p_mu = calculateProcessingDelay(sfc);    // Dựa trên M/M/1
        return t_mu + p_mu;// [cite: 199]
    }

    // 2. Tính trễ truyền dẫn (T_mu) dựa trên khoảng cách giữa các node [cite: 168, 169]
    private double calculateTransmissionDelay(SFCRequest sfc) {
        double totalT = 0;
        for (int i = 0; i < sfc.vnfChain.size() - 1; i++) {
            PhysicalNode src = sfc.vnfChain.get(i).hostNode;
            PhysicalNode dst = sfc.vnfChain.get(i + 1).hostNode;
            if (src != null && dst != null) {
                totalT += getDistanceDelay(src, dst); // Hàm này dùng BFS/Dijkstra để tính trễ giữa 2 node
            }
        }
        return totalT;
    }

    // 3. Tính trễ xử lý (P_mu) theo mô hình M/M/1 [cite: 172, 175]
    private double calculateProcessingDelay(SFCRequest sfc) {
        double totalP = 0;
        double epsilon = 1e-9;
        for (VNFInstance vnfi : sfc.vnfChain) {
            double nu = vnfi.getAllocatedRate(sfc); // nu_mi [cite: 136, 137] Tốc độ xử lý cấp cho SFC
            double lambda = sfc.trafficArrivalRate; // lambda_mu [cite: 112] traffic arrival rate
            if (nu > lambda) {
                totalP += 1.0 / (nu - lambda + epsilon);
            } else {
                totalP += 1000; // Penalty khi vi phạm ràng buộc [cite: 234, 236]
            }
        }
        return totalP;
    }

    // 4. Tính Load của mạng (L = N_var_cpu + N_var_mem + E_var) [cite: 221]
    public double calculateNetworkLoad(List<PhysicalNode> nodes, List<PhysicalEdge> edges) {
        // Công thức (18): L = N_var_cpu + N_var_mem + E_var [cite: 221, 222]
        double cpuVar = calcNodeVar(nodes, "cpu");
        double memVar = calcNodeVar(nodes, "mem");
        double edgeVar = calcEdgeVar(edges); // Hàm này nhận 1 đối số là đúng rồi

        return cpuVar + memVar + edgeVar;
    }

    private double calcNodeVar(List<PhysicalNode> nodes, String type) {
        double sum = 0;
        for (PhysicalNode n : nodes)
            sum += (type.equals("cpu") ? n.cpuUsed/n.cpuCapacity : n.memUsed/n.memCapacity);
        double mean = sum / nodes.size(); // [cite: 205, 217]
        double var = 0;
        for (PhysicalNode n : nodes) {
            double val = (type.equals("cpu") ? n.cpuUsed/n.cpuCapacity : n.memUsed/n.memCapacity);
            var += Math.pow(val - mean, 2);
        }
        return var / nodes.size(); // [cite: 210, 217]
    }

    private double calcEdgeVar(List<PhysicalEdge> edges) {
        double sum = 0;
        for (PhysicalEdge e : edges) sum += (e.bwUsed / e.bandwidth);
        double mean = sum / edges.size();
        double var = 0;
        for (PhysicalEdge e : edges) var += Math.pow((e.bwUsed/e.bandwidth) - mean, 2);
        return var / edges.size();
    }

    private double getDistanceDelay(PhysicalNode src, PhysicalNode dst) {
        if (src.id.equals(dst.id)) {
            return 0.0; // Cùng một node vật lý
        }

        // Tách thông tin Pod từ ID (Ví dụ: "Pod0_Edge_0")
        String srcPod = getPodId(src.id);
        String dstPod = getPodId(dst.id);

        if (srcPod.equals(dstPod)) {
            // Cùng Pod: Đi từ Edge -> Agg -> Edge (2 hops)
            // Mỗi hop Edge-Agg là 2ms
            return 2.0 + 2.0;
        } else {
            // Khác Pod: Đi từ Edge -> Agg -> Core -> Agg -> Edge (4 hops)
            // Edge-Agg là 2ms, Agg-Core là 4ms
            return 2.0 + 4.0 + 4.0 + 2.0;
        }
    }

    // Hàm bổ trợ để lấy tên Pod từ chuỗi ID nút
    private String getPodId(String nodeId) {
        if (nodeId.contains("_")) {
            return nodeId.split("_")[0]; // Trả về "Pod0", "Pod1", ...
        }
        return "Core"; // Nếu là node Core
    }
}