package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * MshOrScalingPolicy: Chua cac logic tinh toan cho thuat toan MSH-OR.
 * <p>
 * Gom 3 phan chinh:
 * <p>
 *   1. Priority Score: quyet dinh VNF nao nen scale truoc
 * <p>
 *   2. Ham muc tieu P: chon host toi uu de clone VNF
 * <p>
 *   3. Cac ham utility: tinh util, hop count, SFC count
 */
public class MshOrScalingPolicy {

    // =========================================================
    // CONSTANTS
    // =========================================================

    /**
     * He so ham muc tieu P cho chon host clone:
     * P = ALPHA*Delay + BETA*Load + GAMMA*Cost
     * <p>
     * Delay = hopCount * 2ms  (uoc luong do tre mang)
     * Load  = CPU utilization cua host dich
     * Cost  = hopCount * 0.1  (chi phi bang thong)
     */
    private static final double ALPHA = 0.4; // trong so delay
    private static final double BETA  = 0.4; // trong so load
    private static final double GAMMA = 0.2; // trong so cost

    /**
     * He so Priority Score cho chon VNF scale:
     * Score = ALPHA_UTIL*util + BETA_SFC*sfc + GAMMA_DELAY*urgency + DELTA_STABILITY*stability
     */
    private static final double ALPHA_UTIL      = 0.3; // trong so util
    private static final double BETA_SFC        = 0.3; // trong so SFC impact
    private static final double GAMMA_DELAY     = 0.3; // trong so delay urgency
    private static final double DELTA_STABILITY = 0.1; // trong so on dinh tai

    /** Nguong util de phat hien VNF qua tai */
    public static final double THRESHOLD = 0.7;


    // =========================================================
    // DATA CLASSES
    // =========================================================

    public enum ScalingType { NONE, VERTICAL, HORIZONTAL }

    public static class ScalingDecision {
        public ScalingType type;
        public SDNHost     targetHost;
        public ScalingDecision(ScalingType t, SDNHost h) {
            this.type = t; this.targetHost = h;
        }
    }

    /**
     * Thong tin mot VNF dang can scale.
     * Chua du lieu de sap xep va ra quyet dinh.
     */
    public static class VnfScalingCandidate {
        public SDNVm   vnf;
        public SDNHost host;
        public int     sfcCount;
        public double  utilization;
        public double  priorityScore; // score tong hop de sap xep
        public double  delayUrgency;  // muc do khan cap ve delay

        public VnfScalingCandidate(SDNVm vnf, SDNHost host,
                                   int sfcCount, double utilization) {
            this.vnf           = vnf;
            this.host          = host;
            this.sfcCount      = sfcCount;
            this.utilization   = utilization;
            this.priorityScore = 0;
            this.delayUrgency  = 0;
        }
    }


    // =========================================================
    // VNF SELECTION: PRIORITY SCORE
    // =========================================================

    /**
     * Lay danh sach VNF dang qua tai va sap xep theo Priority Score.
     * <p>
     * 4 buoc:
     * <p>
     *   1. Thu thap VNF co util >= THRESHOLD
     * <p>
     *   2. Tinh maxSfcCount de chuan hoa sfcScore
     * <p>
     *   3. Tinh Priority Score cho tung VNF
     * <p>
     *   4. Sap xep giam dan: VNF nguy hiem nhat duoc scale truoc
     */
    public List<VnfScalingCandidate> getOverloadedVnfsSorted(
            List<SDNHost>                          allHosts,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        List<VnfScalingCandidate> candidates = new ArrayList<>();

        // --- Buoc 1: Thu thap VNF qua tai ---
        for (Object h : allHosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm  vm   = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue; // bo qua client/server

                double util = getVmUtilization(vm);
                if (util < THRESHOLD) continue;

                int sfcCount = countSFCsUsingVm(vm.getId(), sfcPolicies);
                candidates.add(new VnfScalingCandidate(vm, host, sfcCount, util));
            }
        }

        if (candidates.isEmpty()) return candidates;

        // --- Buoc 2: Tim maxSfcCount de chuan hoa sfcScore ---
        // Vi du: VNF_FW=4 SFC, VNF_IDS=3 SFC -> maxSfcCount=4
        //        sfcScore cua VNF_FW = 4/4 = 1.0
        //        sfcScore cua VNF_IDS = 3/4 = 0.75
        int maxSfcCount = 1;
        for (VnfScalingCandidate c : candidates) {
            if (c.sfcCount > maxSfcCount) maxSfcCount = c.sfcCount;
        }

        // --- Buoc 3: Tinh Priority Score ---
        final int finalMax = maxSfcCount;
        for (VnfScalingCandidate c : candidates) {
            c.priorityScore = calculatePriorityScore(
                    c.vnf, c.utilization, c.sfcCount, finalMax, sfcPolicies);
        }

        // --- Buoc 4: Sap xep giam dan ---
        candidates.sort((a, b) -> Double.compare(b.priorityScore, a.priorityScore));

        return candidates;
    }

    /**
     * Tinh Priority Score tong hop cho 1 VNF.
     * <p>
     * Score = 0.3*utilScore + 0.3*sfcScore + 0.3*urgency + 0.1*stability
     * <p>
     * Giai thich tung thanh phan:
     * <p>
     * [utilScore]: Muc do qua tai hien tai
     *   = min(util, 2.0) / 2.0  (chuan hoa ve [0,1])
     *   util=0.7 -> 0.35, util=1.0 -> 0.5, util=2.0 -> 1.0
     * <p>
     * [sfcScore]: Muc do anh huong den cac chuoi SFC
     *   = sfcCount / maxSfcCount  (chuan hoa ve [0,1])
     *   VNF phuc vu nhieu SFC -> score cao -> uu tien scale
     * <p>
     * [urgency]: Muc do khan cap ve delay (SLA)
     *   = avgDelay / delayThreshold  (chuan hoa ve [0,1])
     *   > 1.0: dang vi pham SLA, rat khan cap
     *   = 1.0: default khi chua co data
     * <p>
     * [stabilityScore]: Tai dang tang hay giam?
     *   stability = util_recent(2.5s) - util_history(2.5s truoc)
     *   > 0: dang tang (nguy hiem hon)
     *   = 0: on dinh
     *   < 0: dang giam (co the tu phuc hoi)
     *   Shift ve [0,1]: stability=0 -> 0.5
     */
    public double calculatePriorityScore(
            SDNVm   vnf,
            double  utilization,
            int     sfcCount,
            int     maxSfcCount,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        // Thanh phan 1: Util score
        double utilScore = Math.min(utilization, 2.0) / 2.0;

        // Thanh phan 2: SFC impact score
        double sfcScore = maxSfcCount > 0
                ? (double) sfcCount / maxSfcCount
                : 0;

        // Thanh phan 3: Delay urgency
        double delayUrgency = 1.0; // default neu chua co data
        double maxUrgency   = 0;
        int    sfcWithData  = 0;

        for (ServiceFunctionChainPolicy p : sfcPolicies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            double avgDelay  = p.getMonitoredDelayAverage();
            double threshold = p.getDelayThresholdMax();
            if (avgDelay > 0 && threshold > 0) {
                double urgency = avgDelay / threshold;
                if (urgency > maxUrgency) maxUrgency = urgency;
                sfcWithData++;
            }
        }
        if (sfcWithData > 0) {
            delayUrgency = Math.min(maxUrgency, 3.0) / 3.0; // cap o 1.0 sau khi chuan hoa
        }

        // Thanh phan 4: Load stability
        double utilRecent  = vnf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 2.5, CloudSim.clock());
        double utilHistory = vnf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 5.0, CloudSim.clock() - 2.5);
        double stability      = utilRecent - utilHistory;
        double stabilityScore = Math.max(0, Math.min(stability + 0.5, 1.0));

        // Tong hop
        double score = ALPHA_UTIL      * utilScore
                + BETA_SFC        * sfcScore
                + GAMMA_DELAY     * delayUrgency
                + DELTA_STABILITY * stabilityScore;

        System.out.printf("%.2f: [PRIORITY] VNF %-10s | " +
                        "util=%.3f(%.2f) | SFC=%d/%d(%.2f) | " +
                        "urgency=%.3f | stability=%.3f(%.2f) | score=%.4f%n",
                CloudSim.clock(), vnf.getName(),
                utilization,  utilScore,
                sfcCount, maxSfcCount, sfcScore,
                delayUrgency,
                stability, stabilityScore,
                score);

        return score;
    }


    // =========================================================
    // HOST SELECTION: HAM MUC TIEU P
    // =========================================================

    /**
     * Tim host toi uu de clone VNF sang, loai tru cac host da dung.
     * <p>
     * Ham P = ALPHA*Delay + BETA*Load + GAMMA*Cost
     *   -> Chon host co P nho nhat (gan + nhe + re)
     * <p>
     * Loai tru:
     *   - Host nguon (src)
     *   - Host da duoc dung trong cung 1 cycle (excludedHostIds)
     *   - Host qua tai (util >= THRESHOLD)
     *   - Host khong du MIPS (availMips < 300)
     */
    public SDNHost findBestTargetExcluding(
            SDNHost src,
            List<SDNHost>                          allHosts,
            Collection<ServiceFunctionChainPolicy> sfcPolicies,
            Set<Integer>                           excludedHostIds) {

        SDNHost bestHost = null;
        double  minP     = Double.MAX_VALUE;

        for (Object h : allHosts) {
            SDNHost candidate = (SDNHost) h;

            if (candidate.getId() == src.getId()) continue;
            if (excludedHostIds != null
                    && excludedHostIds.contains(candidate.getId())) continue;
            if (getCpuUtilization(candidate, sfcPolicies) >= THRESHOLD) continue;
            if (candidate.getAvailableMips() < 300) continue;

            double p = calculateP(src, candidate, sfcPolicies);
            if (p < minP) {
                minP     = p;
                bestHost = candidate;
            }
        }

        return bestHost;
    }

    /**
     * Tinh ham muc tieu P cho 1 cap (src, dst).
     * <p>
     * P = ALPHA * Delay + BETA * Load + GAMMA * Cost
     * <p>
     * Delay: uoc luong do tre mang qua hop count
     *   Cung edge switch:  2 hops -> 4ms
     *   Cung pod:          4 hops -> 8ms
     *   Khac pod:          6 hops -> 12ms
     * <p>
     * Load: CPU utilization cua host dich (co SFC sharing factor)
     * <p>
     * Cost: chi phi bang thong ti le voi hop count
     */
    private double calculateP(
            SDNHost src,
            SDNHost dst,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        double hopCount = estimateHopCount(src, dst);
        double delay    = hopCount * 2.0;
        double load     = getCpuUtilization(dst, sfcPolicies);
        double cost     = hopCount * 0.1;

        return ALPHA * delay + BETA * load + GAMMA * cost;
    }

    /**
     * Uoc tinh hop count dua tren naming convention: host-p{pod}-e{edge}-h{h}
     * <p>
     * Fat-Tree routing:
     *   Cung edge:  2 hops (host -> edge -> host)
     *   Cung pod:   4 hops (host -> edge -> agg -> edge -> host)
     *   Khac pod:   6 hops (host -> edge -> agg -> core -> agg -> edge -> host)
     */
    private double estimateHopCount(SDNHost h1, SDNHost h2) {
        try {
            String[] n1 = h1.getName().split("-"); // ["host","p0","e0","h0"]
            String[] n2 = h2.getName().split("-");
            if (!n1[1].equals(n2[1])) return 6.0; // khac pod
            if (!n1[2].equals(n2[2])) return 4.0; // cung pod, khac edge
            return 2.0;                             // cung edge
        } catch (Exception e) {
            return 4.0; // default
        }
    }


    // =========================================================
    // CPU UTILIZATION
    // =========================================================

    /**
     * Lay util thuc te cua 1 VM trong window 5 giay gan nhat.
     * = MIs_processed / (MIPS_allocated * timeWindow)
     */
    public double getVmUtilization(SDNVm vm) {
        return vm.getMonitoredUtilizationCPU(
                CloudSim.clock() - 5.0, CloudSim.clock());
    }

    /**
     * Tinh CPU utilization cua 1 host, co tinh den VNF Sharing.
     * <p>
     * SFC Sharing Factor:
     *   1 SFC  -> factor = 1.0
     *   2 SFCs -> factor = 1.5  (1 + 1*0.5)
     *   3 SFCs -> factor = 2.0  (1 + 2*0.5)
     *   4 SFCs -> factor = 2.5  (1 + 3*0.5)
     * <p>
     * Muc dich: phan anh dung ap luc tai thuc te khi 1 VNF phuc vu nhieu SFC.
     * Ket qua co the > 1.0 (> 100%) - day la chi so ap luc tai, khong phai util vat ly.
     */
    public double getCpuUtilization(
            SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (host.getVmList().isEmpty()) return 0;

        double totalUsed      = 0;
        double totalAllocated = 0;

        for (Object vmObj : host.getVmList()) {
            SDNVm  vm        = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double baseUtil  = getVmUtilization(vm);

            int    sfcCount      = countSFCsUsingVm(vm.getId(), sfcPolicies);
            double sharingFactor = sfcCount <= 1 ? 1.0
                    : 1.0 + (sfcCount - 1) * 0.5;

            totalUsed      += baseUtil * sharingFactor * allocated;
            totalAllocated += allocated;
        }

        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    /** Fallback: tinh util host khong co SFC info */
    public double getCpuUtilization(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;
        for (Object vmObj : host.getVmList()) {
            SDNVm  vm        = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            totalUsed      += getVmUtilization(vm) * allocated;
            totalAllocated += allocated;
        }
        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }


    // =========================================================
    // HELPERS
    // =========================================================

    /**
     * Dem so SFC dang di qua VM co id = vmId.
     * Dung de tinh SFC sharing factor va priority score.
     */
    public int countSFCsUsingVm(
            int vmId,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (sfcPolicies == null) return 1;
        int count = 0;
        for (ServiceFunctionChainPolicy policy : sfcPolicies) {
            if (policy.isSFIncludedInChain(vmId)) count++;
        }
        return count;
    }
}