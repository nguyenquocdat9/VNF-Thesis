package com.thesis.nfv.model;

class PhysicalNode {
    String id;
    double cpuCapacity; // Total CPU [cite: 106]
    double memCapacity; // Total RAM [cite: 106]
    double cpuUsed;
    double memUsed;

    public PhysicalNode(String id, double cpu, double mem) {
        this.id = id;
        this.cpuCapacity = cpu;
        this.memCapacity = mem;
    }

    // Check if physical node have enought to accomodate more VNF [cite: 358]
    public boolean canAccommodate(double cpuReq, double memReq, double threshold) {
        return (cpuUsed + cpuReq <= cpuCapacity * threshold) &&
                (memUsed + memReq <= memCapacity * threshold);
    }
}