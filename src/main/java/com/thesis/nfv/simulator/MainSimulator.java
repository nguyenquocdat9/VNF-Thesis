package com.thesis.nfv.simulator;

import com.thesis.nfv.model.*;
import com.thesis.nfv.core.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainSimulator {
    public static void main(String[] args) {
        // Các mức CPU thử nghiệm: từ rất nghèo nàn (20) đến dồi dào (100)
        int[] cpuThresholds = {5, 7, 10, 13, 16};

        Map<Integer, Double> mshorDelayMap = new TreeMap<>();
        Map<Integer, Double> greedyDelayMap = new TreeMap<>();
        Map<Integer, Double> mshorLoadMap = new TreeMap<>();
        Map<Integer, Double> greedyLoadMap = new TreeMap<>();

        double highTraffic = 2000.0;
        int k = 10;

        for (int cpuCap : cpuThresholds) {
            System.out.println("\n>>> ĐANG CHẠY THỰC NGHIỆM VỚI CPU CAPACITY = " + cpuCap);

            // 1. Setup Topology
            NetworkTopology topology = new NetworkTopology();
            topology.buildFatTreeCustom(k, cpuCap);
            MetricsCalculator metrics = new MetricsCalculator();

            List<PhysicalNode> allEdgeNodes = topology.allNodes.stream()
                    .filter(n -> n.id.toLowerCase().contains("edge"))
                    .collect(Collectors.toList());

            // 2. Tạo VNF và SFC (Reset mỗi vòng lặp để đảm bảo tính công bằng)
            VNFInstance fw = VNFFactory.createVNF("FIREWALL", "0");
            VNFInstance ids = VNFFactory.createVNF("IDS", "0");
            VNFInstance nat = VNFFactory.createVNF("NAT", "0");
            VNFInstance dpi = VNFFactory.createVNF("DPI", "0");

            List<SFCRequest> sfcList = createSFCs(fw, ids, nat, dpi, highTraffic);
            List<VNFInstance> affectedVNFs = new ArrayList<>(Arrays.asList(fw, ids, nat, dpi));

            PhysicalNode bottleneckNode = allEdgeNodes.stream()
                    .filter(n -> n.id.contains("Pod0_Edge_0")).findFirst().get();

            // Giả lập trạng thái trước khi hỏng
            deployVNF(fw, bottleneckNode);
            deployVNF(ids, bottleneckNode);
            deployVNF(nat, bottleneckNode);
            deployVNF(dpi, bottleneckNode);

            // Bắt đầu lỗi
            bottleneckNode.isFailed = true;

            // --- TEST MSH-OR ---
            MigrationEngine.triggerMigration("MSHOR", affectedVNFs, allEdgeNodes);
            double mshorDelayAvg = sfcList.stream().mapToDouble(metrics::calculateTotalSFCDelay).average().orElse(0);
            double mshorLoad = metrics.calculateNetworkLoad(topology.allNodes, topology.allEdges);
            mshorDelayMap.put(cpuCap, mshorDelayAvg);
            mshorLoadMap.put(cpuCap, mshorLoad);

            /** Xuất ra file DOT */
            if (cpuCap == 5 || cpuCap == 16) { // Chỉ xuất file ở 2 mức cực đoan để so sánh
                String fileName = "topology_cpu_" + cpuCap + ".dot";
                topology.exportToDOT(fileName, affectedVNFs);
            }

            // --- RESET ĐỂ TEST GREEDY ---
            resetVNFsForComparison(affectedVNFs, bottleneckNode);

            // --- TEST GREEDY ---
            List<PhysicalNode> shuffledNodes = new ArrayList<>(allEdgeNodes.stream()
                    .filter(n -> !n.id.contains("Pod0")) // Cấm Greedy chọn Pod 0
                    .toList());
            Collections.shuffle(shuffledNodes); // Xáo trộn để mô phỏng tính ngẫu nhiên của Greedy
            MigrationEngine.triggerMigration("GREEDY", affectedVNFs, shuffledNodes);
            double greedyDelayAvg = sfcList.stream().mapToDouble(metrics::calculateTotalSFCDelay).average().orElse(0);
            double greedyLoad = metrics.calculateNetworkLoad(topology.allNodes, topology.allEdges);
            greedyDelayMap.put(cpuCap, greedyDelayAvg);
            greedyLoadMap.put(cpuCap, greedyLoad);
        }

        System.out.println("\n--- ĐANG XUẤT BIỂU ĐỒ CỘT SO SÁNH BIẾN THIÊN ---");

        // Xuất biểu đồ Độ trễ dưới dạng cột nhóm
        ChartExporter.exportGroupedBarChart(
                "So sánh Độ trễ theo tài nguyên CPU",
                "CPU Capacity của mỗi nút",
                "Delay (ms)",
                mshorDelayMap,
                greedyDelayMap,
                "delay_comparison_bar.png");

        // Xuất biểu đồ Cân bằng tải dưới dạng cột nhóm
        ChartExporter.exportGroupedBarChart(
                "So sánh Chỉ số Cân bằng tải theo tài nguyên CPU",
                "CPU Capacity của mỗi nút",
                "Load Index (L)",
                mshorLoadMap,
                greedyLoadMap,
                "load_comparison_bar.png");
    }

    // HÀM BỔ TRỢ 1: Tạo danh sách SFC cứu trợ
    private static List<SFCRequest> createSFCs(VNFInstance fw, VNFInstance ids, VNFInstance nat, VNFInstance dpi, double traffic) {
        List<SFCRequest> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            SFCRequest sfc = new SFCRequest("SFC_" + i, traffic, 100.0);
            sfc.arrivalRate = traffic;
            sfc.vnfChain.addAll(Arrays.asList(fw, ids, nat, dpi));

            // Link ngược để thuật toán MSH-OR Giai đoạn 1 thấy được mức độ ưu tiên
            if(!fw.sharedBySFCs.contains(sfc)) fw.sharedBySFCs.add(sfc);
            if(!ids.sharedBySFCs.contains(sfc)) ids.sharedBySFCs.add(sfc);
            if(!nat.sharedBySFCs.contains(sfc)) nat.sharedBySFCs.add(sfc);
            if(!dpi.sharedBySFCs.contains(sfc)) dpi.sharedBySFCs.add(sfc);

            list.add(sfc);
        }
        return list;
    }

    // HÀM BỔ TRỢ 2: Reset trạng thái VNF về nút lỗi ban đầu
    private static void resetVNFsForComparison(List<VNFInstance> vnfs, PhysicalNode failedNode) {
        for (VNFInstance v : vnfs) {
            if (v.hostNode != null) {
                v.hostNode.cpuUsed -= v.cpuReq;
                v.hostNode.memUsed -= v.memReq;
            }
            v.hostNode = failedNode;
        }
    }

    private static void deployVNF(VNFInstance vnf, PhysicalNode node) {
        vnf.hostNode = node;
        node.cpuUsed += vnf.cpuReq;
        node.memUsed += vnf.memReq;
    }
}