package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MshOrScalingPolicy - Priority Score 3 tieu chi theo yeu cau thay huong dan.
 *
 * =========================================================================
 * PRIORITY SCORE = a1*C1 + a2*C2 + a3*C3
 * =========================================================================
 *
 * C1 - SLA Failure Rate (ti le SFC dang LOI qua VNF nay)
 *   C1 = (so SFC vi pham SLA) / (tong SFC qua VNF)
 *   vi du: vnf_fw 4 SFC, 1 loi  -> C1 = 1/4 = 0.25
 *          vnf_ids 3 SFC, 2 loi -> C1 = 2/3 = 0.67
 *
 * C2 - Priority-Weighted SLA Breach x Resource Pressure
 *   Tich hop ca muc do vi pham SLA (co priority) lan ap luc tai nguyen CPU.
 *   buoc 1: weighted_breach = [Sum(pri_i x breach_i) / Sum(pri_i)] / 3.0
 *   buoc 2: pressure = min(demand / currentMIPS, 2.0) / 2.0  in [0,1]
 *           demand = util x initMIPS
 *   buoc 3: C2 = weighted_breach x (1 + pressure) / 2         in [0,1]
 *   vi du: vnf_fw breach=0.5 pressure=0.90 -> C2 = 0.5x1.90/2 = 0.475
 *          vnf_ids breach=0.5 pressure=0.40 -> C2 = 0.5x1.40/2 = 0.350
 *          -> vnf_fw uu tien du breach ngang nhau, vi demand/cap cao hon
 *
 * C3 - MIPS Efficiency (so SFC duoc cuu / MIPS bo ra)
 *   C3 = (so SFC vi pham) / MIPS_required   (normalize)
 *   vi du: vnf_fw can 261 MIPS cuu 4 SFC (4/261 = 0.01533)
 *          vnf_ids can 204 MIPS cuu 1 SFC (1/204 = 0.00490)
 *          -> vnf_fw hieu qua hon 3x -> chon fw truoc
 *
 * Trong so: a1=0.35, a2=0.40, a3=0.25
 */
public class MshOrScalingPolicy {

    // =========================================================
    // CAU HINH PRIORITY SFC
    // =========================================================

    static final Map<String, Double> SFC_PRIORITY_MAP = new HashMap<String, Double>() {{
        put("sfc1", 1.0);  // VIP / real-time
        put("sfc2", 0.8);  // quan trong
        put("sfc3", 0.6);  // thong thuong
        put("sfc4", 0.4);  // background
        put("sfc5", 0.9);  // payment / critical
        put("sfc6", 0.3);  // background thap
    }};

    static final double DEFAULT_PRIORITY = 0.5;

    // =========================================================
    // CONSTANTS
    // =========================================================

    /** Trong so C1: SLA Failure Rate - do pham vi anh huong */
    private static final double ALPHA1 = 0.35;

    /** Trong so C2: Priority-Weighted Breach - do muc do khan cap co priority */
    private static final double ALPHA2 = 0.40;

    /** Trong so C3: MIPS Efficiency - do hieu qua dau tu tai nguyen */
    private static final double ALPHA3 = 0.25;

    /** Gioi han breach ratio khi tinh C2 (cap = 3.0x SLA threshold) */
    private static final double MAX_BREACH_CAP = 3.0;

    /** Muc tieu utilization sau Vertical Scale */
    private static final double TARGET_UTIL = 0.8;

    /**
     * Chuan hoa C3: tuong ung kich ban 4 SFC vi pham, delta = 100 MIPS.
     * C3_raw = 4/100 = 0.04 -> C3_normalized = 1.0
     */
    private static final double C3_NORM_CAP = 0.04;

    /** Nguong util de coi VNF la "qua tai" */
    public static final double THRESHOLD = 0.85;


    // =========================================================
    // DATA CLASS
    // =========================================================

    public static class VnfScalingCandidate {
        public SDNVm   vnf;
        public SDNHost host;
        public int     sfcCount;
        public int     violatedCount;
        public double  utilization;
        public double  priorityScore;

        public VnfScalingCandidate(SDNVm vnf, SDNHost host,
                                   int sfcCount, double utilization) {
            this.vnf           = vnf;
            this.host          = host;
            this.sfcCount      = sfcCount;
            this.utilization   = utilization;
            this.violatedCount = 0;
            this.priorityScore = 0;
        }
    }


    // =========================================================
    // VNF SELECTION
    // =========================================================

    /**
     * Thu thap VNF qua tai, tinh Priority Score va sap xep GIAM DAN.
     */
    public List<VnfScalingCandidate> getOverloadedVnfsSorted(
            List<SDNHost>                          allHosts,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        List<VnfScalingCandidate> candidates = new ArrayList<>();

        for (Object h : allHosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                double util = getVmUtilization(vm);
                if (util < THRESHOLD) continue;

                int sfcCount = countSFCsUsingVm(vm.getId(), sfcPolicies);
                candidates.add(new VnfScalingCandidate(vm, host, sfcCount, util));
            }
        }

        if (candidates.isEmpty()) return candidates;

        for (VnfScalingCandidate c : candidates) {
            c.priorityScore = calculatePriorityScore(c.vnf, c.utilization,
                    sfcPolicies, false);
        }

        // Sap xep giam dan theo score - VNF quan trong nhat o dau
        candidates.sort((a, b) -> Double.compare(b.priorityScore, a.priorityScore));
        return candidates;
    }


    // =========================================================
    // PRIORITY SCORE
    // =========================================================

    /**
     * Tinh Priority Score = a1*C1 + a2*C2 + a3*C3.
     *
     * @param vnf       VNF can danh gia
     * @param util      CPU utilization hien tai (window 15s)
     * @param policies  danh sach SFC policies
     * @param printLog  true -> in log [PRIORITY]
     */
    public double calculatePriorityScore(
            SDNVm   vnf,
            double  util,
            Collection<ServiceFunctionChainPolicy> policies,
            boolean printLog) {

        // --- Thu thap thong tin SFC di qua VNF ---
        int    totalSfc            = 0;
        int    violatedSfc         = 0;
        double sumWeightedBreach   = 0.0;  // Sum(pri * breach) cho SFC vi pham
        double sumViolatedPriority = 0.0;  // Sum(pri) cho SFC vi pham

        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            totalSfc++;

            double sfcPri    = SFC_PRIORITY_MAP.getOrDefault(p.getName(), DEFAULT_PRIORITY);
            double avgDelay  = p.getMonitoredDelayAverage();
            double threshold = p.getDelayThresholdMax();
            boolean violated = false;
            double  breach   = 0.0;

            if (avgDelay < 0) {
                // Fallback: chua co delay data, util > 1.0 -> vi pham
                if (util > 1.0) {
                    violated = true;
                    breach   = util;
                }
            } else if (threshold > 0) {
                breach = avgDelay / threshold;
                if (breach > 1.0) violated = true;
            }

            if (violated) {
                violatedSfc++;
                double cappedBreach = Math.min(breach, MAX_BREACH_CAP);
                sumWeightedBreach   += sfcPri * cappedBreach;
                sumViolatedPriority += sfcPri;
            }
        }

        // -----------------------------------------------------------
        // C1 - SLA Failure Rate
        // -----------------------------------------------------------
        // C1 = so SFC vi pham / tong SFC qua VNF
        // VNF nao co ty le SFC bi loi cao hon se duoc uu tien scale.
        // vi du: vnf_fw 4 SFC 1 loi -> C1 = 0.25
        //        vnf_ids 3 SFC 2 loi -> C1 = 0.67 (uu tien hon)
        double C1 = totalSfc > 0 ? (double) violatedSfc / totalSfc : 0.0;

        // -----------------------------------------------------------
        // C2 - Priority-Weighted SLA Breach x Resource Pressure
        // -----------------------------------------------------------
        // Tich hop 2 yeu to:
        //   (a) muc do vi pham SLA co priority: weighted_breach
        //   (b) ap luc tai nguyen CPU: demand / currentMIPS
        //
        // Cong thuc:
        //   weighted_breach = [Sum(pri_i * breach_i) / Sum(pri_i)] / MAX_BREACH_CAP
        //   pressure        = min(demand / currentMIPS, 2.0) / 2.0   in [0, 1]
        //   C2              = weighted_breach * (1 + pressure) / 2    in [0, 1]
        //
        // Y nghia: VNF nao vua co SFC vi pham nang (priority cao)
        //          VUNG co demand vuot capacity nhieu -> C2 cao -> uu tien scale truoc.
        //
        // vi du phan biet:
        //   vnf_fw:  weighted_breach=0.50, demand/cap=1.80 -> pressure=0.90
        //            C2 = 0.50 * (1+0.90)/2 = 0.475
        //   vnf_ids: weighted_breach=0.50, demand/cap=1.20 -> pressure=0.60
        //            C2 = 0.50 * (1+0.60)/2 = 0.400
        //   -> vnf_fw duoc uu tien du breach bang nhau, vi demand/cap cao hon
        double C2 = 0.0;
        if (sumViolatedPriority > 0) {
            // Buoc 1: weighted breach (normalized, in [0,1])
            double avgWeightedBreach = sumWeightedBreach / sumViolatedPriority;
            double weightedBreach    = Math.min(avgWeightedBreach / MAX_BREACH_CAP, 1.0);

            // Buoc 2: resource pressure = demand / currentMIPS
            // Su dung initMIPS de tinh demand (tranh circular dependency sau scale)
            double pressure = 0.5; // gia tri mac dinh khi khong phai ServiceFunction
            if (vnf instanceof ServiceFunction) {
                ServiceFunction sf = (ServiceFunction) vnf;
                double demand      = util * sf.getInitialMips(); // luong CPU can thuc su
                double currentMips = sf.getMips();               // capacity hien tai
                if (currentMips > 0) {
                    // Normalize: cap tai 2.0 (200% overload), scale xuong [0,1]
                    pressure = Math.min(demand / currentMips, 2.0) / 2.0;
                }
            }

            // Buoc 3: nhan hai yeu to, normalize ve [0,1]
            // (1 + pressure) in [1, 2] -> chia 2 -> in [0.5, 1.0]
            // * weightedBreach in [0, 1] -> ket qua in [0, 1]
            C2 = weightedBreach * (1.0 + pressure) / 2.0;
        }

        // -----------------------------------------------------------
        // C3 - MIPS Efficiency
        // -----------------------------------------------------------
        // C3 = (so SFC vi pham) / MIPS_required (normalized)
        // Khi host chi du MIPS cho 1 trong 2 VNF:
        //   chon VNF nao "dang hon" tren moi MIPS bo ra.
        // vi du thay de xuat:
        //   vnf_fw: 261 MIPS, 4 SFC -> 4/261 = 0.01533
        //   vnf_ids: 204 MIPS, 1 SFC -> 1/204 = 0.00490
        //   -> vnf_fw hieu qua hon 3x -> C3(fw) >> C3(ids)
        double C3 = 0.0;
        if (vnf instanceof ServiceFunction && violatedSfc > 0) {
            ServiceFunction sf  = (ServiceFunction) vnf;
            double initMips     = sf.getInitialMips();
            double demand       = util * initMips;
            double requiredMips = demand / TARGET_UTIL;
            double deltaMips    = requiredMips - sf.getMips();

            if (deltaMips > 0) {
                // C3_raw = SFC_violated / delta_MIPS
                double c3Raw = (double) violatedSfc / deltaMips;
                // Normalize: C3_raw / C3_NORM_CAP (0.04), clamp toi 1.0
                C3 = Math.min(c3Raw / C3_NORM_CAP, 1.0);
            } else if (violatedSfc > 0) {
                // MIPS da du nhung van vi pham (truong hop hiem)
                C3 = 0.5;
            }
        }

        // -----------------------------------------------------------
        // Tong hop score
        // -----------------------------------------------------------
        double score = ALPHA1 * C1 + ALPHA2 * C2 + ALPHA3 * C3;

        if (printLog) {
            // Tinh lai pressure chi de in log (da tinh trong C2 o tren)
            double logPressure = 0.5;
            if (vnf instanceof ServiceFunction) {
                ServiceFunction sf = (ServiceFunction) vnf;
                double demand      = util * sf.getInitialMips();
                double currentMips = sf.getMips();
                if (currentMips > 0)
                    logPressure = Math.min(demand / currentMips, 2.0) / 2.0;
            }
            System.out.printf(
                    "%.2f: [PRIORITY] VNF %-10s | " +
                            "C1=%.2f(%d/%d SFC) | C2=%.3f(breach=w-SLA x pres=%.2f) | C3=%.3f(mips-eff) | " +
                            "score=%.4f%n",
                    CloudSim.clock(), vnf.getName(),
                    C1, violatedSfc, totalSfc,
                    C2, logPressure, C3,
                    score);
        }

        return score;
    }


    // =========================================================
    // HOST SELECTION - Ham muc tieu P
    // =========================================================

    /**
     * Tim host toi uu de clone VNF sang (Horizontal Scale).
     *
     * P(src, dst) = 0.4*D + 0.4*L + 0.2*C  -> chon P nho nhat
     *   D = hop_count * 2   (do tre mang, ms)
     *   L = CPU utilization host dich
     *   C = hop_count * 0.1 (chi phi bang thong)
     */
    public SDNHost findBestTargetExcluding(
            SDNHost                                src,
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

            double hopCount = estimateHopCount(src, candidate);
            double D = hopCount * 2.0;
            double L = getCpuUtilization(candidate, sfcPolicies);
            double C = hopCount * 0.1;
            double p = 0.4 * D + 0.4 * L + 0.2 * C;

            if (p < minP) {
                minP     = p;
                bestHost = candidate;
            }
        }

        if (bestHost != null) {
            double hop = estimateHopCount(src, bestHost);
            System.out.printf("       [P-host] best=%-22s | hop=%.0f | P=%.3f%n",
                    bestHost.getName(), hop, minP);
        }

        return bestHost;
    }

    /**
     * Uoc luong hop count theo naming convention Fat-Tree: "host-p{pod}-e{edge}-h{h}"
     * Cung edge switch -> 2 hops
     * Cung pod, khac edge -> 4 hops
     * Khac pod -> 6 hops
     */
    private double estimateHopCount(SDNHost h1, SDNHost h2) {
        try {
            String[] n1 = h1.getName().split("-");
            String[] n2 = h2.getName().split("-");
            if (!n1[1].equals(n2[1])) return 6.0;
            if (!n1[2].equals(n2[2])) return 4.0;
            return 2.0;
        } catch (Exception e) {
            return 4.0;
        }
    }


    // =========================================================
    // UTILIZATION HELPERS
    // =========================================================

    /**
     * Lay CPU utilization cua VNF trong window 15 giay gan nhat.
     */
    public double getVmUtilization(SDNVm vm) {
        return vm.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
    }

    /**
     * Tinh CPU utilization cua host co tinh den Sharing Factor.
     * VNF phuc vu nhieu SFC -> tai thuc te cao hon.
     * Factor = 1 + (sfcCount - 1) * 0.5
     */
    public double getCpuUtilization(
            SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;

        for (Object vmObj : host.getVmList()) {
            SDNVm  vm        = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double baseUtil  = getVmUtilization(vm);
            int    sfcCount  = countSFCsUsingVm(vm.getId(), sfcPolicies);
            double factor    = sfcCount <= 1 ? 1.0 : 1.0 + (sfcCount - 1) * 0.5;

            totalUsed      += baseUtil * factor * allocated;
            totalAllocated += allocated;
        }

        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    /**
     * Dem so SFC dang di qua VNF co vmId nay.
     */
    public int countSFCsUsingVm(
            int vmId,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (sfcPolicies == null) return 1;
        int count = 0;
        for (ServiceFunctionChainPolicy p : sfcPolicies)
            if (p.isSFIncludedInChain(vmId)) count++;
        return count;
    }
}