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

/**
 * PAVScalingPolicy - Priority Score 3 tieu chi.
 * PRIORITY SCORE = a1*C1 + a2*C2 + a3*C3
 * <p>
 * C1 - SLA Failure Rate (ti le SFC dang LOI qua VNF nay)
 *   C1 = (so SFC vi pham SLA) / (tong SFC qua VNF)
 *   vi du: vnf_fw 4 SFC, 1 loi  -> C1 = 1/4 = 0.25
 *          vnf_ids 3 SFC, 2 loi -> C1 = 2/3 = 0.67
 * <p>
 * C2 - Priority-Weighted SLA Breach x Resource Pressure
 *   Tich hop ca muc do vi pham SLA (co priority) lan ap luc tai nguyen CPU.
 *   buoc 1: weighted_breach = [Sum(pri_i x breach_i) / Sum(pri_i)] / 3.0
 *   buoc 2: pressure = min(demand / currentMIPS, 2.0) / 2.0  in [0,1]
 *           demand = util x initMIPS
 *   buoc 3: C2 = weighted_breach x (1 + pressure) / 2         in [0,1]
 *   vi du: vnf_fw breach=0.5 pressure=0.90 -> C2 = 0.5x1.90/2 = 0.475
 *          vnf_ids breach=0.5 pressure=0.40 -> C2 = 0.5x1.40/2 = 0.350
 *          -> vnf_fw uu tien du breach ngang nhau, vi demand/cap cao hon
 * <p>
 * C3 - MIPS Efficiency (so SFC duoc cuu / MIPS bo ra)
 *   C3 = (so SFC vi pham) / MIPS_required   (normalize)
 *   vi du: vnf_fw can 261 MIPS cuu 4 SFC (4/261 = 0.01533)
 *          vnf_ids can 204 MIPS cuu 1 SFC (1/204 = 0.00490)
 *          -> vnf_fw hieu qua hon 3x -> chon fw truoc
 * <p>
 * Trong so: a1=0.35, a2=0.40, a3=0.25
 */
public class PAVScalingPolicy {

    static final Map<String, Double> SFC_PRIORITY_MAP = new HashMap<String, Double>() {{
        put("sfc1", 1.0);  // VIP / real-time
        put("sfc2", 0.8);  // quan trong
        put("sfc3", 0.6);  // thong thuong
        put("sfc4", 0.4);  // background
        put("sfc5", 0.9);  // payment / critical
        put("sfc6", 0.3);  // background thap
    }};

    static final double DEFAULT_PRIORITY = 0.5;

    private static final double ALPHA1 = 0.35; // trong so C1
    private static final double ALPHA2 = 0.40; // trong so C2
    private static final double ALPHA3 = 0.25; // trong so C3
    private static final double MAX_BREACH_CAP = 3.0; // cap breach ratio cho C2
    private static final double TARGET_UTIL = 0.8;    // util muc tieu sau scale
    private static final double C3_NORM_CAP = 0.04;   // 4 SFC / 100 MIPS = 1.0 normalized
    public  static final double THRESHOLD = 0.85;      // nguong qua tai


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


    public double calculatePriorityScore(
            SDNVm   vnf,
            double  util,
            Collection<ServiceFunctionChainPolicy> policies,
            boolean printLog) {

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
                // chua co delay data, dung util lam fallback
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

        // C1 = violated/total SFC
        double C1 = totalSfc > 0 ? (double) violatedSfc / totalSfc : 0.0;

        // C2 = weighted_breach x resource_pressure
        double C2 = 0.0;
        if (sumViolatedPriority > 0) {
            double avgWeightedBreach = sumWeightedBreach / sumViolatedPriority;
            double weightedBreach    = Math.min(avgWeightedBreach / MAX_BREACH_CAP, 1.0);

            double pressure = 0.5;
            if (vnf instanceof ServiceFunction) {
                ServiceFunction sf = (ServiceFunction) vnf;
                double demand      = util * sf.getInitialMips(); // dung initMips tranh vong tron
                double currentMips = sf.getMips();
                if (currentMips > 0) {
                    pressure = Math.min(demand / currentMips, 2.0) / 2.0;
                }
            }

            C2 = weightedBreach * (1.0 + pressure) / 2.0;
        }

        // C3 = SFC cuu duoc / MIPS can cap (chon VNF hieu qua nhat)
        double C3 = 0.0;
        if (vnf instanceof ServiceFunction && violatedSfc > 0) {
            ServiceFunction sf  = (ServiceFunction) vnf;
            double initMips     = sf.getInitialMips();
            double demand       = util * initMips;
            double requiredMips = demand / TARGET_UTIL;
            double deltaMips    = requiredMips - sf.getMips();

            if (deltaMips > 0) {
                double c3Raw = (double) violatedSfc / deltaMips;
                C3 = Math.min(c3Raw / C3_NORM_CAP, 1.0);
            } else if (violatedSfc > 0) {
                C3 = 0.5; // MIPS du nhung van vi pham, truong hop hiem
            }
        }

        double score = ALPHA1 * C1 + ALPHA2 * C2 + ALPHA3 * C3;

        if (printLog) {
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


    public double getVmUtilization(SDNVm vm) {
        return vm.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
    }

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
