package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerTimeSharedMonitor;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Baseline 1: First Fit Scaling
 *
 * Khi VNF qua tai (util > 70%):
 * - Khong co Vertical Scale
 * - Clone VNF sang HOST DAU TIEN tim thay con tai nguyen
 * - Khong dung ham P, khong sap xep theo SFC
 *
 * Muc dich: So sanh voi MSH-OR de thay hieu qua cua ham muc tieu P
 * va co che uu tien VNF theo so SFC.
 */
public class FirstFitNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999902;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.7;

    private boolean monitoringStarted = false;
    private Set<Integer> scaledHosts = null;

    @Override
    public void startEntity() {
        super.startEntity();
        monitoringStarted = false;
        scaledHosts = new HashSet<>();
        System.out.println("### FirstFitNOS.startEntity() - state reset");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledHosts == null) scaledHosts = new HashSet<>();

        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runFirstFitLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            }
        } else {
            super.processEvent(ev);
        }
    }

    private void runFirstFitLogic() {
        List<SDNHost> hosts = getHostList();

        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            if (host.getVmList().isEmpty()) continue;
            if (scaledHosts.contains(host.getId())) continue;

            // Kiem tra tung VNF tren host
            for (Object vmObj : new ArrayList<>(host.getVmList())) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 5.0, CloudSim.clock());
                if (util < THRESHOLD) continue;

                System.out.printf("%.2f: [FirstFit] VNF %s on %s | util=%.1f%%%n",
                        CloudSim.clock(), vm.getName(), host.getName(), util * 100);

                // Tim HOST DAU TIEN con tai nguyen (First Fit)
                SDNHost target = findFirstFitHost(host, hosts);
                if (target != null) {
                    doClone(vm, host, target);
                    scaledHosts.add(host.getId());
                } else {
                    System.out.printf("%.2f: [FirstFit] WARNING: No host available for %s%n",
                            CloudSim.clock(), vm.getName());
                }
                break; // Chi scale 1 VNF moi lan monitor
            }
        }
    }

    /**
     * Chon host dau tien trong danh sach co util < THRESHOLD.
     * Khong tinh den vi tri, hop count hay ham muc tieu.
     */
    private SDNHost findFirstFitHost(SDNHost src, List<SDNHost> allHosts) {
        for (Object h : allHosts) {
            SDNHost candidate = (SDNHost) h;
            if (candidate.getId() == src.getId()) continue;
            if (candidate.getAvailableMips() < 300) continue;

            double util = getHostUtil(candidate);
            if (util < THRESHOLD) {
                return candidate; // Chon ngay host dau tien hop le
            }
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
        cloned.setName(original.getName() + "_ff_" + cloneId);
        cloned.setMiddleboxType(original.getMiddleboxType());
        cloned.setHostName(dst.getName());

        original.setMips(reducedMips);

        System.out.printf("%.2f: [FirstFit] CLONE VNF %s: %s (%.0f->%.0f) -> %s (%.0f)%n",
                CloudSim.clock(), original.getName(),
                src.getName(), originalMips, reducedMips,
                dst.getName(), cloneMips);

        addExtraVm(cloned, this);
    }
}