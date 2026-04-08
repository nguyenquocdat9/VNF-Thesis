package com.thesis.nfv.model;

import java.util.ArrayList;
import java.util.List;

public class SFCRequest {
    public String id;
    public List<VNFInstance> vnfChain = new ArrayList<>(); // Chuỗi VNF có thứ tự [cite: 111, 126]
    public double arrivalRate; // lambda_mu [cite: 112, 126, 172]
    public double maxDelayTolerance; // D_mu^max [cite: 112, 126, 241]

    public SFCRequest(String id, double lambda, double maxDelay) {
        this.id = id;
        this.arrivalRate = lambda;
        this.maxDelayTolerance = maxDelay;
    }
}