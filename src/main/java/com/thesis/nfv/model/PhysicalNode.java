package com.thesis.nfv.model;

public class PhysicalNode {
    public String id;
    public double cpuCapacity; // Total CPU [cite: 106]
    public double memCapacity; // Total RAM [cite: 106]
    public double cpuUsed = 0;
    public double memUsed = 0;
    public boolean isFailed = false; // Phục vụ kịch bản Migration Trigger

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