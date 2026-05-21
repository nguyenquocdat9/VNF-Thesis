package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.*;

/**
 * PAVS (Priority-aware VNF Scaling): Multi-criteria SFC-aware Optimal Resource scaling (Vertical only).
 *
 * Priority Score = a1*C1 + a2*C2 + a3*C3
 *   C1 - SLA Failure Rate:         ti le SFC dang vi pham qua VNF
 *   C2 - Priority-Weighted Breach: muc do nghiem trong x priority SFC vi pham
 *   C3 - MIPS Efficiency:          so SFC cuu duoc / MIPS can cap them
 * Trong so: a1=0.35, a2=0.40, a3=0.25
 */
public class PAVScalingNOS extends NetworkOperatingSystemSimple {

    private static final int    MAX_SCALE_PER_CYCLE = 1;
    private static final int    MONITOR_EVENT       = 999901;
    private static final double MONITOR_INTERVAL    = 30.0;
    private static final double SIM_END_TIME        = 200.0;


    private final PAVScalingPolicy policy = new PAVScalingPolicy();
    private boolean monitoringStarted = false;


    // theo doi ket qua scale (M2, M3)
    static class ScaleSnapshot {
        String vnfName;
        double scaleTime;
        int violatedBefore;
        int totalSfc;
        double weightedDelayBefore;
        Map<String, double[]> sfcDelaySnapshot;
        double deltaMips;

        ScaleSnapshot(String vnfName, double scaleTime,
                      int violated, int total, double wDelay,
                      Map<String, double[]> snapshot, double deltaMips) {
            this.vnfName             = vnfName;
            this.scaleTime           = scaleTime;
            this.violatedBefore      = violated;
            this.totalSfc            = total;
            this.weightedDelayBefore = wDelay;
            this.sfcDelaySnapshot    = snapshot;
            this.deltaMips           = deltaMips;
        }
    }

    static final Map<String, ScaleSnapshot> pendingSnapshots = new LinkedHashMap<>();
    static double cisM1 = 0.0;
    static double cisM2 = 0.0;
    static double cisM3 = 0.0;


    // luu tru WQB va WLE tich luy
    static double wqbAccumulator = 0.0; // sum(pri * waiting * dt), thap hon = tot hon
    // W_q = rho/(mu*(1-rho))
    static double wleAccumulator = 0.0; // sum(pri * Wq * dt), thap hon = tot hon

    static final Map<String, Double> SFC_PRIORITY_MAP = new LinkedHashMap<>();
    static {
        SFC_PRIORITY_MAP.put("sfc1", 1.0);
        SFC_PRIORITY_MAP.put("sfc2", 0.8);
        SFC_PRIORITY_MAP.put("sfc3", 0.6);
        SFC_PRIORITY_MAP.put("sfc4", 0.4);
        SFC_PRIORITY_MAP.put("sfc5", 0.9);
        SFC_PRIORITY_MAP.put("sfc6", 0.3);
    }

    static final Map<String, Double> VNF_MAX_PRIORITY = new LinkedHashMap<>();
    static {
        VNF_MAX_PRIORITY.put("vnf_fw",  1.0);
        VNF_MAX_PRIORITY.put("vnf_ids", 1.0);
        VNF_MAX_PRIORITY.put("vnf_nat", 0.9);
        VNF_MAX_PRIORITY.put("vnf_lb",  1.0);
        VNF_MAX_PRIORITY.put("vnf_enc", 0.9);
    }


    @Override
    public void startEntity() {
        super.startEntity();
        Configuration.SFC_AUTOSCALE_ENABLE               = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM_VERTICAL   = false;
        Configuration.SFC_AUTOSCALE_ENABLE_BW            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_BW = false;
        monitoringStarted = false;
        System.out.println("### PAVScalingNOS.startEntity() - PAVS active | Vertical Scale only");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            runPavsLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                printWqb("PAVS");
                printWle("PAVS");
                printCis("PAVS");
            }
        } else {
            super.processEvent(ev);
        }
    }


    private void runPavsLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        evaluateSnapshots(sfcPolicies);
        logQueueLength("PAVS", hosts);
        accumulateWle(hosts, sfcPolicies);

        List<PAVScalingPolicy.VnfScalingCandidate> overloadedVnfs =
                policy.getOverloadedVnfsSorted(hosts, sfcPolicies);

        if (overloadedVnfs.isEmpty()) return;

        for (PAVScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
            policy.calculatePriorityScore(c.vnf, c.utilization, sfcPolicies, true);
        }

        System.out.printf("%.2f: [PAVS] %d VNF(s) can scale:%n",
                CloudSim.clock(), overloadedVnfs.size());
        for (PAVScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
            System.out.printf("       %-15s | %-22s | util=%.1f%% | SFCs=%d | score=%.4f%n",
                    c.vnf.getName(), c.host.getName(),
                    c.utilization * 100, c.sfcCount, c.priorityScore);
        }

        int scaledThisCycle = 0;
        for (PAVScalingPolicy.VnfScalingCandidate candidate : overloadedVnfs) {
            if (scaledThisCycle >= MAX_SCALE_PER_CYCLE) break;
            doVerticalScaleVnf(candidate.vnf, candidate.host, sfcPolicies);
            scaledThisCycle++;
        }
        if (scaledThisCycle > 0) logLoadVariance("PAVS", hosts);
    }

    private void doVerticalScaleVnf(
            SDNVm vnf, SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return;

        ServiceFunction sf       = (ServiceFunction) vnf;
        double currentMips       = sf.getMips();
        double mipOper           = sf.getMIperOperation() > 0 ? sf.getMIperOperation() : 1;
        double util              = policy.getVmUtilization(vnf);
        int    sfcCount          = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);
        final double TARGET_UTIL = 0.8;
        double initMips          = sf.getInitialMips();
        double lambdaTotal       = util * (initMips / mipOper);
        double lambdaPerSfc      = lambdaTotal / Math.max(sfcCount, 1);
        double demandPerSfc      = lambdaPerSfc * mipOper;
        double totalDemand       = util * initMips;
        double requiredMips      = totalDemand / TARGET_UTIL;
        double delta             = requiredMips - currentMips;

        System.out.printf("%.2f: [PAVS VERTICAL ANALYSIS] VNF %s | SFCs=%d%n",
                CloudSim.clock(), vnf.getName(), sfcCount);
        for (ServiceFunctionChainPolicy p : sfcPolicies) {
            if (p.isSFIncludedInChain(vnf.getId())) {
                System.out.printf("       SFC %-8s | demand=%.1f MIPS (lambda=%.2f req/s)%n",
                        p.getName(), demandPerSfc, lambdaPerSfc);
            }
        }
        System.out.printf("       demand=%.1f | current=%.1f | target=%d%% | required=%.1f | delta=%.1f%n",
                totalDemand, currentMips, (int)(TARGET_UTIL * 100), requiredMips, delta);

        if (delta <= 0) {
            System.out.printf("       -> No scale needed%n");
            return;
        }
        double availMips = host.getAvailableMips();
        if (availMips < delta) {
            System.out.printf("       -> SKIP: host insufficient (avail=%.1f < need=%.1f)%n",
                    availMips, delta);
            return;
        }
        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips      = Math.min(requiredMips, maxMipsPerPe);
        double actualDelta  = newMips - currentMips;

        computeProjectedImprovement(vnf, actualDelta, sfcPolicies);
        sf.setMips(newMips);

        System.out.printf("       -> VERTICAL SCALE | %.1f -> %.1f MIPS (delta=%.1f)%n",
                currentMips, newMips, actualDelta);
    }


    // tinh WLE
    static void accumulateWle(List<SDNHost> hosts,
                               Collection<ServiceFunctionChainPolicy> policies) {
        final double deltaT = 30.0;

        for (Object hObj : hosts) {
            SDNHost host = (SDNHost) hObj;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;
                if (!(vm instanceof ServiceFunction)) continue;

                ServiceFunction sf = (ServiceFunction) vm;
                double util = sf.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util < PAVScalingPolicy.THRESHOLD) continue;

                double mips    = sf.getMips();
                double miPerOp = sf.getMIperOperation() > 0 ? sf.getMIperOperation() : 1.0;
                double mu      = mips / miPerOp;
                double rho     = Math.min(util, 0.999);
                double wq      = rho / (mu * (1.0 - rho));

                for (ServiceFunctionChainPolicy p : policies) {
                    if (!p.isSFIncludedInChain(vm.getId())) continue;
                    double pri = SFC_PRIORITY_MAP.getOrDefault(p.getName(), 0.5);
                    wleAccumulator += pri * wq * deltaT;
                }
            }
        }
    }


    // log do dai hang doi
    static void logQueueLength(String tag, List<SDNHost> hosts) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%.1f: [QUEUE] %-10s |", CloudSim.clock(), tag));
        final double deltaT = 30.0;

        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                int waiting = vm.getCloudletScheduler().getCloudletWaitingList().size();
                int running = vm.getCloudletScheduler().getCloudletExecList().size();
                if (waiting + running > 0) {
                    sb.append(String.format(" %s(w=%d,r=%d)", vm.getName(), waiting, running));
                }
                if (waiting > 0) {
                    String baseName = vm.getName().replaceAll("_clone.*", "");
                    double priority = VNF_MAX_PRIORITY.getOrDefault(baseName, 0.5);
                    wqbAccumulator += priority * waiting * deltaT;
                }
            }
        }
        System.out.println(sb.toString());
    }


    static void logLoadVariance(String tag, List<SDNHost> hosts) {
        if (hosts.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        List<Double> utils = new ArrayList<>();
        int idx = 1;
        for (Object hObj : hosts) {
            SDNHost host = (SDNHost) hObj;
            double total = host.getTotalMips();
            double avail = host.getAvailableMips();
            double util  = total > 0 ? (total - avail) / total : 0.0;
            utils.add(util);
            sb.append(String.format(" host%d=%.2f", idx++, util));
        }
        double mean = 0.0;
        for (double u : utils) mean += u;
        mean /= utils.size();
        double variance = 0.0;
        for (double u : utils) variance += (u - mean) * (u - mean);
        variance /= utils.size();
        System.out.printf("%.1f: [LOAD_VAR] %-10s |%s | variance=%.4f%n",
                CloudSim.clock(), tag, sb.toString(), variance);
    }


    static void computeProjectedImprovement(SDNVm vnf, double deltaMips,
                                             Collection<ServiceFunctionChainPolicy> policies) {
        int    total = 0, violated = 0;
        double c2Before = 0.0;

        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            total++;
            double pri       = PAVScalingPolicy.SFC_PRIORITY_MAP
                    .getOrDefault(p.getName(), PAVScalingPolicy.DEFAULT_PRIORITY);
            double avgDelay  = p.getMonitoredDelayAverage();
            double threshold = p.getDelayThresholdMax();
            boolean isViolating = (avgDelay < 0) || (threshold > 0 && avgDelay > threshold);
            if (isViolating) {
                violated++;
                c2Before += pri * 1.0;
            }
        }

        double c1Before = total > 0 ? (double) violated / total : 0.0;

        Map<String, double[]> sfcSnapshot = new LinkedHashMap<>();
        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            double pri           = PAVScalingPolicy.SFC_PRIORITY_MAP
                    .getOrDefault(p.getName(), PAVScalingPolicy.DEFAULT_PRIORITY);
            double avgDelay      = p.getMonitoredDelayAverage();
            double threshold     = p.getDelayThresholdMax();
            double violatedFlag  = (avgDelay < 0 || (threshold > 0 && avgDelay > threshold)) ? 1.0 : 0.0;
            sfcSnapshot.put(p.getName(), new double[]{pri, violatedFlag, threshold});
        }

        pendingSnapshots.put(vnf.getName(),
                new ScaleSnapshot(vnf.getName(), CloudSim.clock(),
                        violated, total, c2Before, sfcSnapshot, deltaMips));

        System.out.printf(
                "%.1f: [BEFORE] %-10s | C1_before=%.3f(%d/%d SFC) | C2_before=%.3f(sum pri*SFC) | C3_before=%.0f SFC | dMips=%.1f%n",
                CloudSim.clock(), vnf.getName(),
                c1Before, violated, total, c2Before, (double) violated, deltaMips);
    }

    static void evaluateSnapshots(Collection<ServiceFunctionChainPolicy> policies) {
        if (pendingSnapshots.isEmpty()) return;

        for (Map.Entry<String, ScaleSnapshot> entry : pendingSnapshots.entrySet()) {
            ScaleSnapshot snap = entry.getValue();
            int    totalNow = 0, violatedNow = 0;
            double c2Now    = 0.0;

            for (ServiceFunctionChainPolicy p : policies) {
                if (!snap.sfcDelaySnapshot.containsKey(p.getName())) continue;
                totalNow++;
                double[] snapData  = snap.sfcDelaySnapshot.get(p.getName());
                double   pri       = snapData[0];
                double   threshold = snapData[2];
                double   avgDelay  = p.getMonitoredDelayAverage();
                boolean  isViolatingNow;

                if (avgDelay >= 0 && threshold > 0) {
                    isViolatingNow = avgDelay > threshold;
                } else {
                    double vnfUtil = getVnfUtilByName(snap.vnfName, policies);
                    isViolatingNow = (vnfUtil >= 1.0);
                }

                if (isViolatingNow) {
                    violatedNow++;
                    c2Now += pri * 1.0;
                }
            }

            double c1After  = totalNow > 0 ? (double) violatedNow / totalNow : 0.0;
            double c1Before = snap.totalSfc > 0 ? (double) snap.violatedBefore / snap.totalSfc : 0.0;
            double dc1 = c1Before - c1After;
            double dc2 = snap.weightedDelayBefore - c2Now;
            double dc3 = snap.violatedBefore - violatedNow;

            cisM1 += dc1;
            cisM2 += dc2;
            cisM3 += dc3;

            System.out.printf(
                    "%.1f: [AFTER]  %-10s | C1: %.3f->%.3f (DC1=%+.3f) | C2: %.3f->%.3f (DC2=%+.3f) | C3: %.0f->%.0f (DC3=%+.0f) | dMips=%.1f%n",
                    CloudSim.clock(), snap.vnfName,
                    c1Before, c1After, dc1,
                    snap.weightedDelayBefore, c2Now, dc2,
                    (double) snap.violatedBefore, (double) violatedNow, dc3,
                    snap.deltaMips);
        }

        pendingSnapshots.clear();
    }

    private static double getVnfUtilByName(String vnfName,
                                            Collection<ServiceFunctionChainPolicy> policies) {
        return 0.5;
    }


    static void printWle(String tag) {
        System.out.printf("%n### [WLE] %-10s | Weighted Latency Excess = %.3f%n", tag, wleAccumulator);
        System.out.printf("###        (thap hon = SFC priority cao bi tre qua tai it hon, M/M/1 model)%n");
        wleAccumulator = 0.0;
    }

    static void printWqb(String tag) {
        System.out.printf("%n### [WQB] %-10s | Weighted Queue Burden = %.1f%n", tag, wqbAccumulator);
        System.out.printf("###        (thap hon = SFC priority cao duoc phuc vu tot hon)%n");
        wqbAccumulator = 0.0;
    }

    static void printCis(String tag) {
        System.out.printf("%n### [CIS] %-10s | M1_sum=%.3f | M2_sum=%.3f | M3_sum=%.3f%n",
                tag, cisM1, cisM2, cisM3);
        System.out.printf(
                "###   M1=sum(DC1): tong giam ti le SFC vi pham%n" +
                "###   M2=sum(DC2): tong giam Sum(pri*SFC) [KEY METRIC]%n" +
                "###   M3=sum(DC3): tong so SFC vi pham giam duoc%n" +
                "###   Duong = cai thien | Am = xau di%n");
        cisM1 = cisM2 = cisM3 = 0.0;
        pendingSnapshots.clear();
    }
}
