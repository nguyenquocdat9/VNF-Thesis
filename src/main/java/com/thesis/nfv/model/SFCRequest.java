package com.thesis.nfv.model;

import java.util.ArrayList;
import java.util.List;

class SFCRequest {
    String id;
    List<VNFInstance> vnfChain = new ArrayList<>(); // Thứ tự các VNF [cite: 111]
    double trafficArrivalRate; // Tốc độ gói tin đến (lambda) [cite: 112]
    double maxDelayTolerance; // Độ trễ tối đa cho phép [cite: 112, 241]
}