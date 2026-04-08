package com.thesis.nfv.simulator;

import com.thesis.nfv.model.*;
import com.thesis.nfv.core.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainSimulator {
    public static void main(String[] args) {
        double highTraffic = 2000.0;
        // 1. Tăng k lên 10 để mạng rộng hơn (50 Edge Nodes)
        NetworkTopology topology = new NetworkTopology();
        topology.buildFatTree(10);

        MetricsCalculator metrics = new MetricsCalculator();

        // 2. Lấy danh sách Edge Nodes (Lúc này sẽ có 50 nút)
        List<PhysicalNode> allEdgeNodes = topology.allNodes.stream()
                .filter(n -> n.id.toLowerCase().contains("edge"))
                .collect(Collectors.toList());

        // Reset tài nguyên
        for(PhysicalNode n : allEdgeNodes) {
            n.cpuUsed = 0; n.memUsed = 0; n.isFailed = false;
        }

        System.out.println(">>> System ready with: " + allEdgeNodes.size() + " Edge Nodes.");

        // 3. Sử dụng Factory để tạo VNF
        VNFInstance fw = VNFFactory.createVNF("FIREWALL", "0");
        VNFInstance ids = VNFFactory.createVNF("IDS", "0");
        VNFInstance nat = VNFFactory.createVNF("NAT", "0");
        VNFInstance dpi = VNFFactory.createVNF("DPI", "0");

        // 4. Đặt ban đầu vào Pod0_Edge_0
        PhysicalNode bottleneckNode = allEdgeNodes.stream()
                .filter(n -> n.id.contains("Pod0_Edge_0")).findFirst()
                .orElseThrow(() -> new RuntimeException("Can't find Pod0_Edge_0!"));

        deployVNF(fw, bottleneckNode);
        deployVNF(ids, bottleneckNode);
        deployVNF(nat, bottleneckNode);
        deployVNF(dpi, bottleneckNode);

        // 5. Tạo 10 SFC dùng chung (Sharing)
        List<SFCRequest> sfcList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SFCRequest sfc = new SFCRequest("SFC_" + i, highTraffic, 100.0);
            sfc.arrivalRate = highTraffic;
            sfc.vnfChain.add(fw);
            sfc.vnfChain.add(ids);
            sfc.vnfChain.add(nat);
            sfc.vnfChain.add(dpi);

            // Link VNF -> SFC (Dùng cho Giai đoạn 1)
            if(!fw.sharedBySFCs.contains(sfc)) fw.sharedBySFCs.add(sfc);
            if(!ids.sharedBySFCs.contains(sfc)) ids.sharedBySFCs.add(sfc);
            if(!nat.sharedBySFCs.contains(sfc)) nat.sharedBySFCs.add(sfc);

            sfcList.add(sfc);
        }

        // 6. GIẢ LẬP LỖI NÚT
        System.out.println("\n=== KỊCH BẢN: NÚT " + bottleneckNode.id + " BỊ LỖI ===");
        bottleneckNode.isFailed = true;
        List<VNFInstance> affectedVNFs = new ArrayList<>(Arrays.asList(fw, ids, nat, dpi));

        // --- CHẠY THUẬT TOÁN MSH-OR ---
        System.out.println("\n--- [TEST 1] THUẬT TOÁN MSH-OR ---");
        // MSH-OR thông minh nên không sợ danh sách bị xáo trộn, nhưng ta cứ chạy bình thường
        MigrationEngine.triggerMigration("MSHOR", affectedVNFs, allEdgeNodes);
        System.out.print(">> KẾT QUẢ MSH-OR:");
        printFinalResults(sfcList, topology, metrics);

        // --- RESET ĐỂ CHẠY GREEDY ---
        for (VNFInstance vnf : affectedVNFs) {
            if (vnf.hostNode != null) {
                vnf.hostNode.cpuUsed -= vnf.cpuReq;
                vnf.hostNode.memUsed -= vnf.memReq;
            }
            vnf.hostNode = bottleneckNode;
        }

        // --- CHẠY THUẬT TOÁN GREEDY ---
        System.out.println("\n--- [TEST 2] THUẬT TOÁN GREEDY (FIRST-FIT) ---");

        // XÁO TRỘN DANH SÁCH NÚT: Đây là bước quan trọng để tạo ra sự khác biệt
        // Greedy sẽ lấy nút đầu tiên trong danh sách đã shuffle này
        List<PhysicalNode> shuffledNodes = new ArrayList<>(allEdgeNodes);
        Collections.shuffle(shuffledNodes);

        MigrationEngine.triggerMigration("GREEDY", affectedVNFs, shuffledNodes);

        System.out.print(">> KẾT QUẢ GREEDY:");
        printFinalResults(sfcList, topology, metrics);
    }

    private static void deployVNF(VNFInstance vnf, PhysicalNode node) {
        vnf.hostNode = node;
        node.cpuUsed += vnf.cpuReq;
        node.memUsed += vnf.memReq;
        System.out.println("Placed " + vnf.id + " at " + node.id + " (Curent load: CPU " + node.cpuUsed + ")");
    }

    private static void printFinalResults(List<SFCRequest> sfcs, NetworkTopology topo, MetricsCalculator mc) {
        double totalDelay = 0;
        int recovered = 0;

        System.out.println("\n All SFC Delay");
        for (SFCRequest sfc : sfcs) {
            double d = mc.calculateTotalSFCDelay(sfc);
            System.out.println("- " + sfc.id + ": " + String.format("%.2f", d) + " ms");

            totalDelay += d;
            // Nếu trễ thấp (không bị dính penalty 1000ms), coi như thành công
            if (d < 500.0) recovered++;
        }

        // QUAN TRỌNG: Truyền topo.allEdges vào đây thay vì rỗng
        double networkLoad = mc.calculateNetworkLoad(topo.allNodes, topo.allEdges);

        System.out.println("\n>> Algorithm result:");
        System.out.println(">> Number SFC recovered: " + recovered + "/10");
        System.out.println(">> Total system Delay: " + String.format("%.2f", totalDelay) + " ms");
        System.out.println(">> Load Balancing (L): " + String.format("%.6f", networkLoad));
    }
}