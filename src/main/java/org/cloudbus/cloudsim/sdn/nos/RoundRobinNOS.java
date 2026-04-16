package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerTimeSharedMonitor;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Baseline 2: Round Robin Scaling
 *
 * Khi VNF qua tai (util > 70%):
 * - Khong co Vertical Scale
 * - Clone VNF sang host theo VONG TRON (round robin)
 * - Giu counter, moi lan scale tang counter len 1
 *
 * Muc dich: So sanh voi MSH-OR de thay hieu qua cua ham muc tieu P
 * va co che uu tien VNF theo so SFC.
 */
public class RoundRobinNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999904;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.7;

    private boolean monitoringStarted = false;
    private Set<Integer> scaledHosts = null;
    private int rrCounter = 0; // Round Robin counter

    @Override
    public void startEntity() {
        super.startEntity();
        monitoringStarted = false;
        scaledHosts = new HashSet<>();
        rrCounter = 0;
        System.out.println("### RoundRobinNOS.startEntity() - state reset");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledHosts == null) scaledHosts = new HashSet<>();

        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runRoundRobinLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            }
        } else {
            super.processEvent(ev);
        }
    }

    private void runRoundRobinLogic() {
        try {
            List<SDNHost> hosts = getHostList();
            if (hosts == null || hosts.isEmpty()) return;
            if (rrCounter >= hosts.size()) rrCounter = 0;

            for (Object h : hosts) {
                SDNHost host = (SDNHost) h;
                if (host.getVmList().isEmpty()) continue;
                if (scaledHosts.contains(host.getId())) continue;

                for (Object vmObj : new ArrayList<>(host.getVmList())) {
                    SDNVm vm = (SDNVm) vmObj;
                    if (vm.getMiddleboxType() == null) continue;

                    double util = vm.getMonitoredUtilizationCPU(
                            CloudSim.clock() - 5.0, CloudSim.clock());
                    if (util < THRESHOLD) continue;

                    System.out.printf("%.2f: [RoundRobin] VNF %s on %s | util=%.1f%%%n",
                            CloudSim.clock(), vm.getName(), host.getName(), util * 100);

                    SDNHost target = findRoundRobinHost(host, hosts);
                    if (target != null) {
                        doClone(vm, host, target);
                        scaledHosts.add(host.getId());
                    } else {
                        System.out.printf("%.2f: [RoundRobin] WARNING: No host for %s%n",
                                CloudSim.clock(), vm.getName());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("### RoundRobin ERROR at " + CloudSim.clock() + ": " + e);
            e.printStackTrace();
        }
    }

    /**
     * Chon host theo Round Robin: tang counter, chon hosts[counter % N].
     * Bo qua host hien tai va host da day.
     */
    private SDNHost findRoundRobinHost(SDNHost src, List<SDNHost> allHosts) {
        int size = allHosts.size();
        if (size == 0) return null;

        for (int i = 0; i < size; i++) {
            rrCounter = (rrCounter + 1) % size;
            // Dam bao index hop le
            if (rrCounter < 0 || rrCounter >= size) {
                rrCounter = 0;
            }
            SDNHost candidate = (SDNHost) allHosts.get(rrCounter);
            if (candidate.getId() == src.getId()) continue;
            if (candidate.getAvailableMips() < 300) continue;
            double util = getHostUtil(candidate);
            if (util < THRESHOLD) return candidate;
        }
        return null;
    }

    private double getHostUtil(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;
        for (Object vmObj : host.getVmList()) {
            SDNVm vm = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double used = vm.getMonitoredUtilizationCPU(
                    CloudSim.clock() - 5.0, CloudSim.clock());
            totalUsed      += used * allocated;
            totalAllocated += allocated;
        }
        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    private void doClone(SDNVm original, SDNHost src, SDNHost dst) {
        double originalMips = original.getMips();
        double cloneMips    = originalMips * 0.3;
        double reducedMips  = originalMips * 0.7;

        int cloneId = SDNVm.getUniqueVmId();
        SDNVm cloned = new SDNVm(
                cloneId,
                original.getUserId(),
                cloneMips,
                original.getNumberOfPes(),
                original.getRam(),
                original.getBw(),
                original.getSize(),
                original.getVmm(),
                new CloudletSchedulerTimeSharedMonitor((long) original.getMips(), Double.POSITIVE_INFINITY),
                CloudSim.clock(),
                Double.POSITIVE_INFINITY
        );
        cloned.setName(original.getName() + "_rr_" + cloneId);
        cloned.setMiddleboxType(original.getMiddleboxType());
        cloned.setHostName(dst.getName());

        original.setMips(reducedMips);

        System.out.printf("%.2f: [RoundRobin] CLONE VNF %s: %s (%.0f->%.0f) -> %s (%.0f) [RR idx=%d]%n",
                CloudSim.clock(), original.getName(),
                src.getName(), originalMips, reducedMips,
                dst.getName(), cloneMips, rrCounter);

        addExtraVm(cloned, this);
    }
}