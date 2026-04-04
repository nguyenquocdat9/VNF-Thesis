package com.thesis.nfv.model;

public class PhysicalEdge {
    public String sourceId;
    public String destId;
    public double bandwidth; // maximum bandwidth [cite: 108]
    public double propagationDelay; // propagationDelay (D_ej) [cite: 108]
    public double bwUsed = 0;

    public PhysicalEdge(String src, String dst, double bw, double delay) {
        this.sourceId = src;
        this.destId = dst;
        this.bandwidth = bw;
        this.propagationDelay = delay;
    }
}