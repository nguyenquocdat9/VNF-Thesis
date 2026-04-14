package com.thesis.nfv.simulator;

import com.thesis.nfv.model.*;
import com.thesis.nfv.core.*;
import com.thesis.nfv.algorithm.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainSimulator {
    public static void main(String[] args) {
        int[] cpuThresholds = {5, 7, 10, 13, 16};

        Map<Integer, Double> mshorDelayMap = new TreeMap<>();
        Map<Integer, Double> greedyDelayMap = new TreeMap<>();
        Map<Integer, Double> mshorLoadMap = new TreeMap<>();
        Map<Integer, Double> greedyLoadMap = new TreeMap<>();

        double highTraffic = 2000.0;
        int k = 10;

        System.out.println("=================================================================================");
        System.out.printf("%-15s | %-20s | %-20s | %-15s\n", "CPU CAP", "ALGORITHM", "AVG DELAY (ms)", "LOAD INDEX (L)");
        System.out.println("---------------------------------------------------------------------------------");

        for (int cpuCap : cpuThresholds) {
            // 1. Setup Topology
            NetworkTopology topology = new NetworkTopology();
            topology.buildFatTreeCustom(k, cpuCap);
            MetricsCalculator metrics = new MetricsCalculator();

            List<PhysicalNode> allEdgeNodes = topology.allNodes.stream()
                    .filter(n -> n.id.toLowerCase().contains("edge"))
                    .collect(Collectors.toList());

            // 2. Setup VNFs & SFCs
            VNFInstance fw = VNFFactory.createVNF("FIREWALL", "0");
            VNFInstance ids = VNFFactory.createVNF("IDS", "0");
            VNFInstance nat = VNFFactory.createVNF("NAT", "0");
            VNFInstance dpi = VNFFactory.createVNF("DPI", "0");

            List<SFCRequest> sfcList = createSFCs(fw, ids, nat, dpi, highTraffic);
            List<VNFInstance> affectedVNFs = new ArrayList<>(Arrays.asList(fw, ids, nat, dpi));

            PhysicalNode bottleneckNode = allEdgeNodes.stream()
                    .filter(n -> n.id.contains("Pod0_Edge_0")).findFirst().get();

            deployVNF(fw, bottleneckNode);
            deployVNF(ids, bottleneckNode);
            deployVNF(nat, bottleneckNode);
            deployVNF(dpi, bottleneckNode);

            bottleneckNode.isFailed = true;

            // --- TEST MSH-OR ---
            MigrationEngine.triggerMigration("MSHOR", affectedVNFs, allEdgeNodes);
            double mshorDelayAvg = sfcList.stream().mapToDouble(metrics::calculateTotalSFCDelay).average().orElse(0);
            double mshorLoad = metrics.calculateNetworkLoad(topology.allNodes, topology.allEdges);

            // Log dòng MSH-OR
            System.out.printf("%-15d | %-20s | %-20.4f | %-15.6f\n", cpuCap, "MSH-OR (Proposed)", mshorDelayAvg, mshorLoad);

            mshorDelayMap.put(cpuCap, mshorDelayAvg);
            mshorLoadMap.put(cpuCap, mshorLoad);

            if (cpuCap == 5 || cpuCap == 16) {
                topology.exportToDOT("topology_cpu_" + cpuCap + ".dot", affectedVNFs);
            }

            // --- RESET & TEST GREEDY ---
            resetVNFsForComparison(affectedVNFs, bottleneckNode);

            List<PhysicalNode> greedyCandidates = allEdgeNodes.stream()
                    .filter(n -> !n.id.contains("Pod0"))
                    .collect(Collectors.toList());
            Collections.shuffle(greedyCandidates);

            MigrationEngine.triggerMigration("GREEDY", affectedVNFs, greedyCandidates);
            double greedyDelayAvg = sfcList.stream().mapToDouble(metrics::calculateTotalSFCDelay).average().orElse(0);
            double greedyLoad = metrics.calculateNetworkLoad(topology.allNodes, topology.allEdges);

            // Log dòng Greedy
            System.out.printf("%-15s | %-20s | %-20.4f | %-15.6f\n", "", "Greedy (FF)", greedyDelayAvg, greedyLoad);
            System.out.println("---------------------------------------------------------------------------------");

            greedyDelayMap.put(cpuCap, greedyDelayAvg);
            greedyLoadMap.put(cpuCap, greedyLoad);
        }

        // Xuất biểu đồ
        ChartExporter.exportGroupedBarChart("So sánh Độ trễ", "CPU Capacity", "Delay (ms)", mshorDelayMap, greedyDelayMap, "delay_comparison_bar.png");
        ChartExporter.exportGroupedBarChart("So sánh Cân bằng tải", "CPU Capacity", "Load Index", mshorLoadMap, greedyLoadMap, "load_comparison_bar.png");

        System.out.println("\n>>> THỰC NGHIỆM HOÀN TẤT. BIỂU ĐỒ ĐÃ ĐƯỢC XUẤT RA FILE PNG.");
    }

    private static List<SFCRequest> createSFCs(VNFInstance fw, VNFInstance ids, VNFInstance nat, VNFInstance dpi, double traffic) {
        List<SFCRequest> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            SFCRequest sfc = new SFCRequest("SFC_" + i, traffic, 100.0);
            sfc.arrivalRate = traffic;
            sfc.vnfChain.addAll(Arrays.asList(fw, ids, nat, dpi));
            fw.sharedBySFCs.add(sfc); ids.sharedBySFCs.add(sfc); nat.sharedBySFCs.add(sfc); dpi.sharedBySFCs.add(sfc);
            list.add(sfc);
        }
        return list;
    }

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