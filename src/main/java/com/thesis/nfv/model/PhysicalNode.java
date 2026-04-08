package com.thesis.nfv.model;

public class PhysicalNode {
    public String id;
    public double cpuCapacity;
    public double memCapacity;
    public double cpuUsed = 0;
    public double memUsed = 0;
    public boolean isFailed = false;

    public PhysicalNode(String id, double cpu, double mem) {
        this.id = id;
        this.cpuCapacity = cpu;
        this.memCapacity = mem;
    }

    public boolean canAccommodate(double cpuReq, double memReq, double threshold) {
        // threshold truyền vào từ thuật toán đang là 1.0
        double eps = 0.00001;
        boolean cpuOk = (this.cpuUsed + cpuReq) <= (this.cpuCapacity * threshold + eps);
        boolean memOk = (this.memUsed + memReq) <= (this.memCapacity * threshold + eps);
        return cpuOk && memOk;
    }
}