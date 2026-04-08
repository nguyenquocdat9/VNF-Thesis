package com.thesis.nfv.core;

import com.thesis.nfv.model.VNFInstance;
import java.util.ArrayList;

public class VNFFactory {

    // Tạo VNF theo loại với cấu hình chuẩn
    public static VNFInstance createVNF(String type, String idSuffix) {
        VNFInstance vnf = new VNFInstance();
        vnf.sharedBySFCs = new ArrayList<>();

        switch (type.toUpperCase()) {
            case "FIREWALL":
                vnf.id = "FW_" + idSuffix;
                vnf.cpuReq = 2.0; vnf.memReq = 2.0; vnf.processingCapacity = 5000.0;
                break;
            case "IDS":
                vnf.id = "IDS_" + idSuffix;
                vnf.cpuReq = 3.0; vnf.memReq = 2.0; vnf.processingCapacity = 4000.0;
                break;
            case "NAT":
                vnf.id = "NAT_" + idSuffix;
                vnf.cpuReq = 1.0; vnf.memReq = 1.0; vnf.processingCapacity = 6000.0;
                break;
            case "DPI":
                vnf.id = "DPI_" + idSuffix;
                vnf.cpuReq = 4.0; vnf.memReq = 4.0; vnf.processingCapacity = 3000.0;
                break;
            default:
                vnf.id = "VNF_" + idSuffix;
                vnf.cpuReq = 2.0; vnf.memReq = 2.0; vnf.processingCapacity = 5000.0;
        }
        return vnf;
    }
}