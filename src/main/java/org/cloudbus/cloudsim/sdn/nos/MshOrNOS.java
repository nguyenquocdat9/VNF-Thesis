package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerSpaceSharedMonitor;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.*;

public class MshOrNOS extends NetworkOperatingSystemSimple {

    private final MshOrScalingPolicy policy = new MshOrScalingPolicy();

    private static final int    MONITOR_EVENT    = 999901;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final int    MAX_VERTICAL     = 3;
    private static final double IMPACT_THRESHOLD = 0.4;

    private boolean monitoringStarted = false;
    private Map<Integer, Integer> verticalScaleCount = null;
    private Set<Integer> horizontalScaled = null;

    @Override
    public void startEntity() {
        super.startEntity();
        Configuration.SFC_AUTOSCALE_ENABLE_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;
        monitoringStarted = false;
        verticalScaleCount = new HashMap<>();
        horizontalScaled   = new HashSet<>();
        System.out.println("### MshOrNOS.startEntity() - VM AutoScaler disabled, MSH-OR active");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (verticalScaleCount == null) verticalScaleCount = new HashMap<>();
        if (horizontalScaled == null)   horizontalScaled   = new HashSet<>();
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            runMshOrLogic();
            if (CloudSim.clock() < SIM_END_TIME)
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        } else {
            super.processEvent(ev);
        }
    }

    private void runMshOrLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        List<MshOrScalingPolicy.VnfScalingCandidate> overloadedVnfs =
                policy.getOverloadedVnfsSorted(hosts, sfcPolicies);

        if (!overloadedVnfs.isEmpty()) {
            System.out.printf("%.2f: [MSH-OR] %d overloaded VNF(s):%n",
                    CloudSim.clock(), overloadedVnfs.size());
            for (MshOrScalingPolicy.VnfScalingCandidate c : overloadedVnfs)
                System.out.printf("       %-15s | %-22s | util=%.1f%% | SFCs=%d%n",
                        c.vnf.getName(), c.host.getName(), c.utilization * 100, c.sfcCount);
        }

        Set<Integer> usedTargets = new HashSet<>();

        for (MshOrScalingPolicy.VnfScalingCandidate candidate : overloadedVnfs) {
            SDNVm   vnf  = candidate.vnf;
            SDNHost host = candidate.host;
            if (horizontalScaled.contains(host.getId())) continue;

            int vCount    = getVerticalScaleCount(host.getId());
            double availMips  = host.getAvailableMips();
            double neededMips = vnf.getMips() * 0.2;
            double newMips    = Math.min(vnf.getMips() * 1.2,
                    host.getTotalMips() / host.getNumberOfPes());

            // M/M/1 impact check
            double impact = estimateVerticalScaleImpact(vnf, newMips, sfcPolicies);

            if (vCount < MAX_VERTICAL && availMips >= neededMips && impact > IMPACT_THRESHOLD) {
                doVerticalScaleVnf(vnf, host, newMips, impact, vCount + 1);
                verticalScaleCount.put(host.getId(), vCount + 1);
            } else {
                if (impact <= IMPACT_THRESHOLD) {
                    System.out.printf("%.2f: [MSH-OR] VERTICAL SKIP - impact=%.4f < %.1f -> Horizontal%n",
                            CloudSim.clock(), impact, IMPACT_THRESHOLD);
                    verticalScaleCount.put(host.getId(), MAX_VERTICAL);
                }
                SDNHost bestTarget = policy.findBestTargetExcluding(
                        host, hosts, sfcPolicies, usedTargets);
                if (bestTarget != null) {
                    doHorizontalScaleVnf(vnf, host, bestTarget, sfcPolicies);
                    horizontalScaled.add(host.getId());
                    verticalScaleCount.put(host.getId(), 0);
                    usedTargets.add(bestTarget.getId());
                } else {
                    System.out.printf("%.2f: [MSH-OR] WARNING: No target for VNF %s%n",
                            CloudSim.clock(), vnf.getName());
                }
            }
        }
    }

    // ===== M/M/1 IMPACT =====

    private double estimateVerticalScaleImpact(SDNVm vnf, double newMips,
                                               Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        if (!(vnf instanceof ServiceFunction)) return 1.0;
        ServiceFunction sf = (ServiceFunction) vnf;
        double mipOper = sf.getMIperOperation();
        if (mipOper <= 0) mipOper = 1;

        double muBefore = vnf.getMips() / mipOper;
        double muAfter  = newMips / mipOper;
        double util     = policy.getVmUtilization(vnf);
        double lambda   = util * muBefore;

        if (lambda <= 0) return 1.0;
        if (lambda >= muBefore) lambda = muBefore * 0.99;
        if (lambda >= muAfter)  return 0;

        double improvement = (1.0 / (muBefore - lambda)) - (1.0 / (muAfter - lambda));
        int sfcCount = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);
        double totalImpact = improvement * sfcCount;

        System.out.printf("%.2f: [MSH-OR IMPACT] VNF %s | D_improve=%.4f | SFCs=%d | total=%.4f | %s%n",
                CloudSim.clock(), vnf.getName(), improvement, sfcCount, totalImpact,
                totalImpact > IMPACT_THRESHOLD ? "SCALE" : "SKIP");

        return totalImpact;
    }

    // ===== VERTICAL SCALE =====

    private void doVerticalScaleVnf(SDNVm vnf, SDNHost host,
                                    double newMips, double impact, int count) {
        double currentMips = vnf.getMips();
        vnf.setMips(newMips);
        System.out.printf("%.2f: [MSH-OR] VERTICAL SCALE #%d | VNF %s on %s | " +
                        "MIPS %.0f->%.0f | impact=%.4f%n",
                CloudSim.clock(), count, vnf.getName(), host.getName(),
                currentMips, newMips, impact);
    }

    // ===== HORIZONTAL SCALE (CLONE) =====

    private void doHorizontalScaleVnf(SDNVm original, SDNHost src, SDNHost dst,
                                      Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        if (!(original instanceof ServiceFunction)) return;
        ServiceFunction sf = (ServiceFunction) original;
        int sfcCount = policy.countSFCsUsingVm(sf.getId(), sfcPolicies);

        ServiceFunction newSf = new ServiceFunction(
                SDNVm.getUniqueVmId(), sf.getUserId(), sf.getMips(), sf.getNumberOfPes(),
                sf.getRam(), sf.getBw(), sf.getSize(), sf.getVmm(),
                new CloudletSchedulerSpaceSharedMonitor(Configuration.TIME_OUT),
                sf.getStartTime(), Double.POSITIVE_INFINITY);
        newSf.setName(sf.getName() + "-mshor-" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName());

        System.out.printf("%.2f: [MSH-OR] HORIZONTAL SCALE | VNF %s | SFCs=%d | %s -> %s%n",
                CloudSim.clock(), sf.getName(), sfcCount, src.getName(), dst.getName());

        // addDuplicatedSF tu goi addExtraVm - KHONG goi them
        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);
    }

    public int getVerticalScaleCount(int hostId) {
        return verticalScaleCount.getOrDefault(hostId, 0);
    }
}