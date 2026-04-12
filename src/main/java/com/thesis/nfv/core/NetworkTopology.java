package com.thesis.nfv.core;

import com.thesis.nfv.model.PhysicalEdge;
import com.thesis.nfv.model.PhysicalNode;
import com.thesis.nfv.model.VNFInstance;

import java.util.ArrayList;
import java.util.List;

public class NetworkTopology {
    public List<PhysicalNode> allNodes = new ArrayList<>();
    public List<PhysicalEdge> allEdges = new ArrayList<>();

    // Hàm xây dựng Fat-Tree tổng quát với tham số k
    public void buildFatTree(int k) {
        buildFatTreeCustom(k, 50.0); // Mặc định là 50 như cũ
    }

    // HÀM MỚI: Cho phép tùy chỉnh CPU của Edge Node để làm thực nghiệm
    public void buildFatTreeCustom(int k, double edgeCpuCap) {
        if (k % 2 != 0) throw new IllegalArgumentException("k phải là số chẵn!");

        allNodes.clear();
        allEdges.clear();

        // 1. Core Nodes
        int numCore = (k / 2) * (k / 2);
        List<PhysicalNode> coreNodes = new ArrayList<>();
        for (int i = 0; i < numCore; i++) {
            PhysicalNode core = new PhysicalNode("Core_" + i, 100, 256);
            allNodes.add(core);
            coreNodes.add(core);
        }

        // 2. Pods
        for (int p = 0; p < k; p++) {
            List<PhysicalNode> podAggNodes = new ArrayList<>();
            List<PhysicalNode> podEdgeNodes = new ArrayList<>();

            for (int i = 0; i < k / 2; i++) {
                PhysicalNode agg = new PhysicalNode("Pod" + p + "_Agg_" + i, 40, 64);
                // SỬ DỤNG edgeCpuCap Ở ĐÂY
                PhysicalNode edge = new PhysicalNode("Pod" + p + "_Edge_" + i, edgeCpuCap, 64);

                allNodes.add(agg);
                allNodes.add(edge);
                podAggNodes.add(agg);
                podEdgeNodes.add(edge);
            }

            // Kết nối Edge-Agg (2ms)
            for (PhysicalNode e : podEdgeNodes) {
                for (PhysicalNode a : podAggNodes) {
                    allEdges.add(new PhysicalEdge(e.id, a.id, 10000, 2.0));
                }
            }

            // Kết nối Agg-Core (4ms)
            for (int i = 0; i < podAggNodes.size(); i++) {
                int startCoreIndex = i * (k / 2);
                for (int j = 0; j < k / 2; j++) {
                    allEdges.add(new PhysicalEdge(podAggNodes.get(i).id, coreNodes.get(startCoreIndex + j).id, 10000, 4.0));
                }
            }
        }
    }

    public void exportToDOT(String fileName, List<VNFInstance> vnfList) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph FatTree {\n");
        // Cấu hình chung cho sơ đồ
        sb.append("  rankdir=TB;\n"); // Vẽ từ trên xuống dưới (Core -> Agg -> Edge)
        sb.append("  node [fontname=\"Arial\", fontsize=12, shape=circle, style=filled];\n");
        sb.append("  edge [color=gray60, penwidth=0.8];\n");

        for (PhysicalNode node : allNodes) {
            String color = "white"; // Mặc định nút màu trắng
            String label = node.id;

            if (node.isFailed) {
                color = "tomato"; // Nút lỗi màu đỏ cam
                label += " (FAILED)";
            } else {
                // Kiểm tra nếu nút có chứa VNF nào thì tô màu xanh da trời
                for (VNFInstance vnf : vnfList) {
                    if (vnf.hostNode != null && vnf.hostNode.id.equals(node.id)) {
                        color = "lightblue";
                        label += "\\n[" + vnf.id + "]"; // Hiển thị tên VNF bên dưới tên nút
                        break;
                    }
                }
            }
            sb.append(String.format("  \"%s\" [fillcolor=\"%s\", label=\"%s\"];\n", node.id, color, label));
        }

        for (PhysicalEdge edge : allEdges) {
            sb.append(String.format("  \"%s\" -- \"%s\";\n", edge.sourceId, edge.destId));
        }

        sb.append("}\n");

        try (java.io.PrintWriter out = new java.io.PrintWriter(fileName)) {
            out.println(sb.toString());
            System.out.println("[INFO] Đã xuất sơ đồ mạng: " + fileName);
        } catch (Exception e) { e.printStackTrace(); }
    }
}