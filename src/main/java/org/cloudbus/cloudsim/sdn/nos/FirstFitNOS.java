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
 * FirstFitNOS -- Baseline 3: Vertical Scale theo thu tu duyet host/vm.
 *
 * Tieu chi chon VNF: VNF dau tien trong danh sach qua tai (khong sort).
 * Thu tu duyet: for(host : hosts) for(vm : host.getVmList()).
 * Khong xet SFC priority, khong xet util hay queue.
 * Chi co Vertical Scale, khong co Horizontal Scale.
 */
public class FirstFitNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999906;
    private static final double MONITOR_INTERVAL = 30.0;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.85;
    private static final double TARGET_UTIL      = 0.8;

    private static class Candidate {
        final SDNVm   vnf;
        final SDNHost host;
        final double  util;
        Candidate(SDNVm vnf, SDNHost host, double util) {
            this.vnf  = vnf;
            this.host = host;
            this.util = util;
        }
    }

    private boolean monitoringStarted = false;

    @Override
    public void startEntity() {
        super.startEntity();
        Configuration.SFC_AUTOSCALE_ENABLE              = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM_VERTICAL   = false;
        Configuration.SFC_AUTOSCALE_ENABLE_BW            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_BW = false;
        monitoringStarted = false;
        System.out.println("### FirstFitNOS started -- Vertical Scale only, first-fit no-repeat");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            runLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                PAVScalingNOS.printWqb("FirstFit");
                PAVScalingNOS.printWle("FirstFit");
                PAVScalingNOS.printCis("FirstFit");
            }
        } else {
            super.processEvent(ev);
        }
    }

    private void runLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> policies = sfcForwarder.getAllPolicies();

        PAVScalingNOS.evaluateSnapshots(policies);
        PAVScalingNOS.logQueueLength("FirstFit", hosts);
        PAVScalingNOS.accumulateWle(hosts, policies);

        // Thu thap tat ca VNF qua tai -- GIU NGUYEN thu tu duyet host/vm
        List<Candidate> overloaded = new ArrayList<>();
        for (Object hObj : hosts) {
            SDNHost host = (SDNHost) hObj;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;
                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util >= THRESHOLD) {
                    overloaded.add(new Candidate(vm, host, util));
                }
            }
        }

        if (overloaded.isEmpty()) return;

        long seed = System.currentTimeMillis();
        Collections.shuffle(overloaded, new Random(seed));

        System.out.printf("%.2f: [FIRST-FIT] shuffle seed=%d | %d overloaded VNF(s), scaling: %s%n",
                CloudSim.clock(), seed, overloaded.size(),
                overloaded.isEmpty() ? "-" : overloaded.get(0).vnf.getName());

        doVerticalScale(overloaded.get(0), policies);
        PAVScalingNOS.logLoadVariance("FirstFit", hosts);
    }

    private void doVerticalScale(Candidate c,
                                 Collection<ServiceFunctionChainPolicy> policies) {
        if (!(c.vnf instanceof ServiceFunction)) return;

        ServiceFunction sf = (ServiceFunction) c.vnf;
        double util        = sf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
        double demand      = util * sf.getMips();
        double required    = demand / TARGET_UTIL;
        double delta       = required - sf.getMips();

        System.out.printf("%.2f: [FF-VERT] %-10s | util=%.3f | delta=%.1f%n",
                CloudSim.clock(), sf.getName(), util, delta);

        if (delta <= 0) {
            System.out.println("       -> no scale needed");
            return;
        }

        double avail = c.host.getAvailableMips();
        if (avail < delta) {
            System.out.printf("       -> SKIP: avail=%.1f < delta=%.1f%n", avail, delta);
            return;
        }

        double maxPerPe = c.host.getTotalMips() / c.host.getNumberOfPes();
        double newMips  = Math.min(required, maxPerPe);
        double actual   = newMips - sf.getMips();
        double oldMips  = sf.getMips();

        // Snapshot TRUOC setMips() -- do C1/C2/C3 chinh xac
        PAVScalingNOS.computeProjectedImprovement(c.vnf, actual, policies);

        sf.setMips(newMips);

        System.out.printf("       -> %.1f -> %.1f MIPS (+%.1f)%n", oldMips, newMips, actual);
    }
}
