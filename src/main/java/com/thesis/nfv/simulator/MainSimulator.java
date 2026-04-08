package com.thesis.nfv.simulator;

import com.thesis.nfv.model.*;
import com.thesis.nfv.core.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainSimulator {
    public static void main(String[] args) {
        // 1. Khởi tạo Topology k=6
        NetworkTopology topology = new NetworkTopology();
        topology.buildFatTreeK6();
        MetricsCalculator metrics = new MetricsCalculator();

        // 2. Lấy danh sách 18 Edge Nodes và ĐẢM BẢO CHÚNG TRỐNG TÀI NGUYÊN
        List<PhysicalNode> allEdgeNodes = topology.allNodes.stream()
                .filter(n -> n.id.toLowerCase().contains("edge"))
                .collect(Collectors.toList());

        for(PhysicalNode n : allEdgeNodes) {
            n.cpuUsed = 0;
            n.memUsed = 0;
            n.isFailed = false;
        }

        System.out.println(">>> System curent ready nodes: " + allEdgeNodes.size() + " Edge Nodes.");

        // 3. Tạo VNF với tài nguyên hợp lý (CPU 2.0, Mem 2.0, Cap 5000)
        VNFInstance fw = createVNF("Firewall", 2.0, 2.0, 5000.0);
        VNFInstance ids = createVNF("IDS", 3.0, 2.0, 5000.0);
        VNFInstance nat = createVNF("NAT", 1.0, 1.0, 5000.0);
        VNFInstance dpi = createVNF("DPI", 2.0, 2.0, 5000.0);

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
            SFCRequest sfc = new SFCRequest("SFC_" + i, 1000.0, 100.0);
            sfc.vnfChain.add(fw);
            sfc.vnfChain.add(ids);
            sfc.vnfChain.add(nat);

            // Link VNF -> SFC (Dùng cho Giai đoạn 1)
            if(!fw.sharedBySFCs.contains(sfc)) fw.sharedBySFCs.add(sfc);
            if(!ids.sharedBySFCs.contains(sfc)) ids.sharedBySFCs.add(sfc);
            if(!nat.sharedBySFCs.contains(sfc)) nat.sharedBySFCs.add(sfc);

            sfcList.add(sfc);
        }

        // 6. GIẢ LẬP LỖI NÚT VÀ CHẠY MSH-OR
        System.out.println("\n=== KỊCH BẢN: NÚT " + bottleneckNode.id + " BỊ LỖI ===");
        bottleneckNode.isFailed = true;

        // Gom danh sách VNF cần cứu trợ
        List<VNFInstance> affectedVNFs = new ArrayList<>(Arrays.asList(fw, ids, nat, dpi));
        System.out.println("Number VNF need migrate: " + affectedVNFs.size());

        MSHORAlgorithm mshor = new MSHORAlgorithm();
        System.out.println("\n Begin MSH-OR Algorithm");
        System.out.println("Check before running: Number of Edge Nodes : " + allEdgeNodes.size());
        mshor.runMigration(affectedVNFs, allEdgeNodes);

        // 7. IN KẾT QUẢ CUỐI CÙNG
        printFinalResults(sfcList, topology, metrics);
    }

    private static VNFInstance createVNF(String id, double cpu, double mem, double cap) {
        VNFInstance v = new VNFInstance();
        v.id = id; v.cpuReq = cpu; v.memReq = mem; v.processingCapacity = cap;
        v.sharedBySFCs = new ArrayList<>(); // Khởi tạo list tránh NullPointer
        return v;
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