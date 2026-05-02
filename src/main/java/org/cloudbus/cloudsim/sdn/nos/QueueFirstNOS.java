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
 * QueueFirstNOS -- Baseline 2: Vertical Scale theo queue dai nhat.
 *
 * Tieu chi chon VNF: so cloudlet dang cho (waitingList) nhieu nhat.
 * Khong xet SFC priority.
 * Chi co Vertical Scale, khong co Horizontal Scale.
 */
public class QueueFirstNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999905;
    private static final double MONITOR_INTERVAL = 30.0;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.85;
    private static final double TARGET_UTIL      = 0.8;

    private static class Candidate {
        final SDNVm   vnf;
        final SDNHost host;
        final int     queueLen;
        Candidate(SDNVm vnf, SDNHost host, int queue) {
            this.vnf      = vnf;
            this.host     = host;
            this.queueLen = queue;
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
        System.out.println("### QueueFirstNOS started -- Vertical Scale only, by longest queue");
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
                MshOrNOS.printWqb("QueueFirst");
                MshOrNOS.printWle("QueueFirst");
                MshOrNOS.printCis("QueueFirst");
            }
        } else {
            super.processEvent(ev);
        }
    }

    private void runLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> policies = sfcForwarder.getAllPolicies();

        MshOrNOS.evaluateSnapshots(policies);
        MshOrNOS.logQueueLength("QueueFirst", hosts);
        MshOrNOS.accumulateWle(hosts, policies);

        // Thu thap tat ca VNF qua tai vao Candidate list
        List<Candidate> overloaded = new ArrayList<>();
        for (Object hObj : hosts) {
            SDNHost host = (SDNHost) hObj;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;
                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util >= THRESHOLD) {
                    int queue = vm.getCloudletScheduler()
                            .getCloudletWaitingList().size();
                    overloaded.add(new Candidate(vm, host, queue));
                }
            }
        }

        if (overloaded.isEmpty()) return;

        // Sap xep: queue DAI NHAT len dau
        overloaded.sort((a, b) -> Integer.compare(b.queueLen, a.queueLen));

        System.out.printf("%.2f: [QUEUE-FIRST] %d overloaded VNF(s), scaling longest queue first%n",
                CloudSim.clock(), overloaded.size());
        for (Candidate c : overloaded) {
            System.out.printf("       %-12s queue=%d%n", c.vnf.getName(), c.queueLen);
        }

        // Scale dung 1 VNF/cycle -- VNF co queue dai nhat
        doVerticalScale(overloaded.get(0), policies);
    }

    private void doVerticalScale(Candidate c,
                                 Collection<ServiceFunctionChainPolicy> policies) {
        if (!(c.vnf instanceof ServiceFunction)) return;

        ServiceFunction sf = (ServiceFunction) c.vnf;
        double util        = sf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
        double demand      = util * sf.getInitialMips();
        double required    = demand / TARGET_UTIL;
        double delta       = required - sf.getMips();

        System.out.printf("%.2f: [QF-VERT] %-10s | util=%.3f | queue=%d | delta=%.1f%n",
                CloudSim.clock(), sf.getName(), util, c.queueLen, delta);

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

        // Snapshot TRUOC setMips() -- do C1/C2/C3 chinh xac
        MshOrNOS.computeProjectedImprovement(c.vnf, actual, policies);

        sf.setMips(newMips);

        System.out.printf("       -> %.1f -> %.1f MIPS (+%.1f)%n",
                sf.getMips(), newMips, actual);
    }
}