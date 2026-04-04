package com.thesis.nfv.model;

import java.util.ArrayList;
import java.util.List;

class VNFInstance {
    String id;
    String type;
    double cpuReq; // Tài nguyên VNF cần [cite: 115]
    double memReq;
    double processingCapacity; // processingCapacity (C_mi) [cite: 131]
    PhysicalNode hostNode; // The physical node cantaining this VNF
    List<SFCRequest> sharedBySFCs = new ArrayList<>(); // List of SFC using this VNF [cite: 194]

    // Tính tốc độ xử lý cấp cho 1 SFC cụ thể (nu_mi) [cite: 137]
    public double getAllocatedRate(SFCRequest sfc) {
        // Giả sử chia đều tài nguyên xử lý cho các SFC đang dùng chung [cite: 133]
        return processingCapacity / sharedBySFCs.size();
    }
}
