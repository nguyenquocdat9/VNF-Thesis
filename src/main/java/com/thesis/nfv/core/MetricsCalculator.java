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

    // 1. Tính tổng trễ của 1 SFC (D_mu = T_mu + P_mu)
    public double calculateTotalSFCDelay(SFCRequest sfc) {
        double t_mu = calculateTransmissionDelay(sfc);
        double p_mu_total = 0;

        for (VNFInstance vnf : sfc.vnfChain) {
            p_mu_total += calculateProcessingDelay(vnf, sfc);
        }
        return t_mu + p_mu_total;
    }

    // 2. Tính trễ truyền dẫn (T_mu) dựa trên khoảng cách giữa các node [cite: 168, 169]
    private double calculateTransmissionDelay(SFCRequest sfc) {
        double totalT = 0;
        for (int i = 0; i < sfc.vnfChain.size() - 1; i++) {
            PhysicalNode src = sfc.vnfChain.get(i).hostNode;
            PhysicalNode dst = sfc.vnfChain.get(i + 1).hostNode;
            if (src != null && dst != null) {
                totalT += getDistanceDelay(src, dst);
            }
        }
        return totalT;
    }

    // 3. Tính trễ xử lý (P_mu) theo mô hình M/M/1
    public double calculateProcessingDelay(VNFInstance vnf, SFCRequest sfc) {
        // Nếu node hỏng hoặc chưa đặt VNF, phạt trễ 1000ms
        if (vnf.hostNode == null || vnf.hostNode.isFailed) return 1000.0;

        double nu = vnf.processingCapacity;
        double lambda = sfc.arrivalRate;

        if (nu <= lambda) return 1000.0;
        return (1.0 / (nu - lambda)) * 1000.0; // Đổi sang ms
    }

    // 4. Tính Load của mạng (L = N_var_cpu + N_var_mem + E_var) [cite: 221]
    public double calculateNetworkLoad(List<PhysicalNode> nodes, List<PhysicalEdge> edges) {
        // Kiểm tra tránh chia cho 0 gây NaN
        if (nodes == null || nodes.isEmpty()) return 0.0;

        double cpuVar = calcNodeVar(nodes, "cpu");
        double memVar = calcNodeVar(nodes, "mem");

        // Nếu edges rỗng, coi như phương sai băng thông bằng 0
        double edgeVar = (edges == null || edges.isEmpty()) ? 0.0 : calcEdgeVar(edges);

        return cpuVar + memVar + edgeVar;
    }

    private double calcNodeVar(List<PhysicalNode> nodes, String type) {
        double sum = 0;
        for (PhysicalNode n : nodes) {
            double cap = type.equals("cpu") ? n.cpuCapacity : n.memCapacity;
            if (cap == 0) continue;
            sum += (type.equals("cpu") ? n.cpuUsed/cap : n.memUsed/cap);
        }
        double mean = sum / nodes.size();
        double var = 0;
        for (PhysicalNode n : nodes) {
            double cap = type.equals("cpu") ? n.cpuCapacity : n.memCapacity;
            double val = (cap == 0) ? 0 : (type.equals("cpu") ? n.cpuUsed/cap : n.memUsed/cap);
            var += Math.pow(val - mean, 2);
        }
        return var / nodes.size();
    }

    private double calcEdgeVar(List<PhysicalEdge> edges) {
        if (edges.isEmpty()) return 0.0;
        double sum = 0;
        for (PhysicalEdge e : edges) sum += (e.bwUsed / e.bandwidth);
        double mean = sum / edges.size();
        double var = 0;
        for (PhysicalEdge e : edges) var += Math.pow((e.bwUsed/e.bandwidth) - mean, 2);
        return var / edges.size();
    }

    private double getDistanceDelay(PhysicalNode src, PhysicalNode dst) {
        if (src.id.equals(dst.id)) return 0.0;
        String srcPod = getPodId(src.id);
        String dstPod = getPodId(dst.id);
        if (srcPod.equals(dstPod)) return 4.0; // Edge -> Agg -> Edge
        return 12.0; // Edge -> Agg -> Core -> Agg -> Edge
    }

    // Hàm bổ trợ để lấy tên Pod từ chuỗi ID nút
    private String getPodId(String nodeId) {
        if (nodeId.contains("_")) return nodeId.split("_")[0];
        return "Core";
    }
}