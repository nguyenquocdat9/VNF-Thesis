package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerSpaceSharedMonitor;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MshOrNOS extends NetworkOperatingSystemSimple {

    private final MshOrScalingPolicy policy = new MshOrScalingPolicy();

    private static final int    MONITOR_EVENT    = 999901;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final int    MAX_VERTICAL     = 3;

    private boolean monitoringStarted = false;
    private Map<Integer, Integer> verticalScaleCount = null;
    private Set<Integer> horizontalScaled = null;

    // ===== LIFECYCLE =====

    @Override
    public void startEntity() {
        super.startEntity();
        // Tat VM scaling cua AutoScaler framework, MSH-OR se xu ly
        // Giu lai BW scaling de network hoat dong dung
        Configuration.SFC_AUTOSCALE_ENABLE_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;

        // Reset toan bo state moi lan simulation chay
        monitoringStarted = false;
        verticalScaleCount = new HashMap<>();
        horizontalScaled   = new HashSet<>();
        System.out.println("### MshOrNOS.startEntity() - VM AutoScaler disabled, MSH-OR active");
    }

    @Override
    public void processEvent(SimEvent ev) {
        // Dam bao state luon duoc khoi tao
        if (verticalScaleCount == null) verticalScaleCount = new HashMap<>();
        if (horizontalScaled == null)   horizontalScaled   = new HashSet<>();

        // Schedule monitoring tu lan processEvent dau tien
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runMshOrLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            }
        } else {
            super.processEvent(ev);
        }
    }

    // ===== MAIN LOGIC =====

    private void runMshOrLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies =
                sfcForwarder.getAllPolicies();

        // Buoc 0: Lay danh sach VNF qua tai, sap xep theo so SFC giam dan
        List<MshOrScalingPolicy.VnfScalingCandidate> overloadedVnfs =
                policy.getOverloadedVnfsSorted(hosts, sfcPolicies);

        if (!overloadedVnfs.isEmpty()) {
            System.out.printf("%.2f: [MSH-OR] %d overloaded VNF(s):%n",
                    CloudSim.clock(), overloadedVnfs.size());
            for (MshOrScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
                System.out.printf("       %-15s | %-22s | util=%.1f%% | SFCs=%d%n",
                        c.vnf.getName(), c.host.getName(),
                        c.utilization * 100, c.sfcCount);
            }
        }

        // Track host da duoc dung lam target trong lan scale nay
        // Dam bao moi VNF duoc clone vao host khac nhau
        Set<Integer> usedTargets = new HashSet<>();

        for (MshOrScalingPolicy.VnfScalingCandidate candidate : overloadedVnfs) {
            SDNVm   vnf  = candidate.vnf;
            SDNHost host = candidate.host;

            // Skip neu host nay da horizontal scale roi
            if (horizontalScaled.contains(host.getId())) continue;

            int vCount = getVerticalScaleCount(host.getId());
            double availMips  = host.getAvailableMips();
            double neededMips = vnf.getMips() * 0.2;

            // Buoc 1: Thu Vertical Scale truoc
            if (vCount < MAX_VERTICAL && availMips >= neededMips) {
                doVerticalScaleVnf(vnf, host, sfcPolicies);
            } else {
                // Buoc 2: Horizontal Scale - tim host toi uu chua duoc dung
                SDNHost bestTarget = policy.findBestTargetExcluding(
                        host, hosts, sfcPolicies, usedTargets);

                if (bestTarget != null) {
                    doHorizontalScaleVnf(vnf, host, bestTarget, sfcPolicies);
                    horizontalScaled.add(host.getId());
                    verticalScaleCount.put(host.getId(), 0);
                    usedTargets.add(bestTarget.getId()); // Danh dau da dung
                } else {
                    System.out.printf("%.2f: [MSH-OR] WARNING: No target for VNF %s%n",
                            CloudSim.clock(), vnf.getName());
                }
            }
        }
    }

    // ===== VERTICAL SCALE =====

    /**
     * Tang MIPS cua VNF len 20%.
     * Kiem tra host con du MIPS truoc khi tang.
     */
    private void doVerticalScaleVnf(SDNVm vnf, SDNHost host,
                                    Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        double currentMips = vnf.getMips();
        double availMips   = host.getAvailableMips();
        double increase    = currentMips * 0.2;

        if (availMips < increase) {
            verticalScaleCount.put(host.getId(), MAX_VERTICAL);
            return;
        }

        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips = Math.min(currentMips + increase, maxMipsPerPe);

        // Tinh impact M/M/1 truoc khi scale
        double impact = estimateVerticalScaleImpact(vnf, newMips, sfcPolicies);

        if (impact <= 0) {
            System.out.printf("%.2f: [MSH-OR] VERTICAL SKIP - no delay improvement for %s -> Horizontal%n",
                    CloudSim.clock(), vnf.getName());
            verticalScaleCount.put(host.getId(), MAX_VERTICAL);
            return;
        }

        int count = verticalScaleCount.getOrDefault(host.getId(), 0) + 1;
        verticalScaleCount.put(host.getId(), count);
        vnf.setMips(newMips);

        System.out.printf("%.2f: [MSH-OR] VERTICAL SCALE #%d | VNF %s | " +
                        "MIPS %.0f->%.0f | impact=%.4f%n",
                CloudSim.clock(), count, vnf.getName(),
                currentMips, newMips, impact);
    }

    /**
     * Uoc tinh muc do cai thien delay tong the neu tang MIPS cua VNF.
     * Dung mo hinh hang doi M/M/1: D = 1/(mu - lambda)
     * Impact = (D_before - D_after) * so_SFC_di_qua_VNF
     */
    private double estimateVerticalScaleImpact(SDNVm vnf,
                                               double newMips,
                                               Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return 1.0;
        ServiceFunction sf = (ServiceFunction) vnf;
        double mipOper = sf.getMIperOperation();
        if (mipOper <= 0) mipOper = 1;

        double currentMips = vnf.getMips();
        double muBefore = currentMips / mipOper;
        double muAfter  = newMips / mipOper;

        double lambda = estimateLambda(vnf, sfcPolicies);
        if (lambda <= 0) return 1.0;
        if (lambda >= muBefore) lambda = muBefore * 0.99;
        if (lambda >= muAfter)  return 0; // Scale khong du -> Horizontal

        double delayBefore = 1.0 / (muBefore - lambda);
        double delayAfter  = 1.0 / (muAfter  - lambda);
        double improvement = delayBefore - delayAfter;
        int sfcCount = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);

        double totalImpact = improvement * sfcCount;

        // Chi scale neu cai thien delay > 0.1 giay tren tong the
        // Duoi nguong nay, chi phi scale khong xung dang
        double IMPACT_THRESHOLD = 0.6;

        System.out.printf("%.2f: [MSH-OR IMPACT] VNF %s | D: %.4f->%.4f | " +
                        "improve=%.4f | SFCs=%d | total=%.4f | %s%n",
                CloudSim.clock(), vnf.getName(),
                delayBefore, delayAfter, improvement, sfcCount, totalImpact,
                totalImpact > IMPACT_THRESHOLD ? "SCALE" : "SKIP");

        return totalImpact > IMPACT_THRESHOLD ? totalImpact : 0;
    }

    /**
     * Uoc tinh arrival rate lambda (req/giay) dua tren
     * tong so request trong 5 giay gan nhat cua tat ca SFC di qua VNF nay.
     */
    private double estimateLambda(SDNVm vnf,
                                  Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return 0;
        ServiceFunction sf = (ServiceFunction) vnf;

        double mipOper = sf.getMIperOperation();
        if (mipOper <= 0) mipOper = 1;

        // lambda = util * mu = util * (MIPS / mipOper)
        // Lay util thuc te tu monitoring window
        double util = policy.getVmUtilization(vnf);
        double mu   = vnf.getMips() / mipOper;
        double lambda = util * mu;

        System.out.printf("%.2f: [LAMBDA] VNF %s | util=%.3f | mu=%.2f | lambda=%.2f%n",
                CloudSim.clock(), vnf.getName(), util, mu, lambda);

        return lambda;
    }

    // ===== HORIZONTAL SCALE (CLONE) =====

    /**
     * Clone VNF sang host dich dung dung API cua framework.
     * - Dung ServiceFunction (khong phai SDNVm) de traffic tu dong chia
     * - Goi sfcForwarder.addDuplicatedSF() de redirect traffic
     * - Goi redistributeDuplicatedPathBandwidthAllChain() de cap nhat BW
     */
    private void doHorizontalScaleVnf(SDNVm original, SDNHost src, SDNHost dst,
                                      Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(original instanceof ServiceFunction)) return;

        ServiceFunction sf = (ServiceFunction) original;
        int sfcCount = policy.countSFCsUsingVm(sf.getId(), sfcPolicies);

        CloudletSchedulerSpaceSharedMonitor clSch =
                new CloudletSchedulerSpaceSharedMonitor(Configuration.TIME_OUT);

        ServiceFunction newSf = new ServiceFunction(
                SDNVm.getUniqueVmId(),
                sf.getUserId(),
                sf.getMips(),
                sf.getNumberOfPes(),
                sf.getRam(),
                sf.getBw(),
                sf.getSize(),
                sf.getVmm(),
                clSch,
                sf.getStartTime(),
                Double.POSITIVE_INFINITY
        );

        newSf.setName(sf.getName() + "-mshor-" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName());

        System.out.printf("%.2f: [MSH-OR] HORIZONTAL SCALE | VNF %s | SFCs=%d | " +
                        "%s -> clone on %s%n",
                CloudSim.clock(), sf.getName(), sfcCount,
                src.getName(), dst.getName());

        // addDuplicatedSF tu goi addExtraVm ben trong — KHONG goi them
        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);
    }

    // ===== HELPERS =====

    public int getVerticalScaleCount(int hostId) {
        return verticalScaleCount.getOrDefault(hostId, 0);
    }
}