package com.thesis.nfv.model;

class PhysicalEdge {
    String sourceId;
    String destId;
    double bandwidth; // maximum bandwidth [cite: 108]
    double propagationDelay; // propagationDelay (D_ej) [cite: 108]
    double bwUsed;

    public PhysicalEdge(String src, String dst, double bw, double delay) {
        this.sourceId = src;
        this.destId = dst;
        this.bandwidth = bw;
        this.propagationDelay = delay;
    }
}