package com.thesis.nfv.model;

import java.util.ArrayList;
import java.util.List;

public class VNFInstance {
    public String id;
    public String type; // Loại VNF [cite: 117]
    public double cpuReq; // [cite: 115, 126]
    public double memReq; // [cite: 115, 126]
    public double processingCapacity; // C_mi' [cite: 131]
    public PhysicalNode hostNode; // Vị trí hiện tại y_mi^ni [cite: 156, 164]

    // Danh sách các SFC đang dùng chung VNFI này (VNFI Sharing) [cite: 50, 131, 194]
    public List<SFCRequest> sharedBySFCs = new ArrayList<>();

    public double getAllocatedRate(SFCRequest sfc) {
        // nu_mi^mu: Tốc độ xử lý cấp cho mỗi SFC (Giả sử chia đều) [cite: 133, 137]
        if (sharedBySFCs.isEmpty()) return processingCapacity;
        return processingCapacity / sharedBySFCs.size();
    }
}