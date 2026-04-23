package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerSpaceSharedMonitor;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Baseline 2: Best Fit Scaling (Bin Packing)
 *
 * Khi VNF qua tai (util > 70%):
 * - Khong co Vertical Scale
 * - Khong co M/M/1 impact check, khong co Priority Score
 * - Clone VNF sang HOST CO UTILIZATION CAO NHAT con du tai nguyen
 *   (Best Fit = bin packing, gom VMs vao cang it host cang tot)
 * - Dung sfcForwarder.addDuplicatedSF() de traffic tu dong chia
 *
 * So sanh voi MSH-OR:
 * - Best Fit toi uu resource packing nhung khong tinh SFC priority
 * - MSH-OR toi uu Priority Score + M/M/1 + Vertical Scale + ham P
 */
public class BestFitNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999903;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.7;

    private boolean monitoringStarted = false;
    private Set<Integer> scaledHosts  = null;

    @Override
    public void startEntity() {
        super.startEntity();
        monitoringStarted = false;
        scaledHosts = new HashSet<>();
        System.out.println("### BestFitNOS.startEntity() - state reset");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledHosts == null) scaledHosts = new HashSet<>();
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            runBestFitLogic();
            if (CloudSim.clock() < SIM_END_TIME)
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        } else {
            super.processEvent(ev);
        }
    }

    private void runBestFitLogic() {
        List<SDNHost> hosts = getHostList();

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

                System.out.printf("%.2f: [BestFit] VNF %s on %s | util=%.1f%%%n",
                        CloudSim.clock(), vm.getName(), host.getName(), util * 100);

                // Tim HOST CO UTILIZATION CAO NHAT con du tai nguyen
                SDNHost target = findBestFitHost(host, hosts);
                if (target != null) {
                    doClone(vm, host, target);
                    scaledHosts.add(host.getId());
                } else {
                    System.out.printf("%.2f: [BestFit] WARNING: No host for %s%n",
                            CloudSim.clock(), vm.getName());
                }
                break;
            }
        }
    }

    /**
     * Best Fit: chon host co utilization cao nhat ma van con du tai nguyen.
     * Muc tieu: gom VMs vao cang it host cang tot (bin packing).
     * Khac voi FirstFit (chon dau tien) va MSH-OR (chon theo ham P).
     */
    private SDNHost findBestFitHost(SDNHost src, List<SDNHost> allHosts) {
        SDNHost bestHost = null;
        double maxUtil   = -1;

        for (Object h : allHosts) {
            SDNHost candidate = (SDNHost) h;
            if (candidate.getId() == src.getId()) continue;
            if (candidate.getAvailableMips() < 300) continue;

            double util = getHostUtil(candidate);
            if (util >= THRESHOLD) continue; // qua tai, bo qua

            // Best Fit: chon host co util CAO NHAT (packed nhat)
            if (util > maxUtil) {
                maxUtil  = util;
                bestHost = candidate;
            }
        }

        if (bestHost != null) {
            System.out.printf("%.2f: [BestFit] Target: %s (util=%.1f%%)%n",
                    CloudSim.clock(), bestHost.getName(), maxUtil * 100);
        }
        return bestHost;
    }

    private double getHostUtil(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;
        for (Object vmObj : host.getVmList()) {
            SDNVm vm = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double used = vm.getMonitoredUtilizationCPU(
                    CloudSim.clock() - 5.0, CloudSim.clock());
            totalUsed += used * allocated;
            totalAllocated += allocated;
        }
        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    /**
     * Clone VNF dung sfcForwarder de traffic tu dong chia.
     * Dung ServiceFunction (khong phai SDNVm) de framework nhan dien dung.
     */
    private void doClone(SDNVm original, SDNHost src, SDNHost dst) {
        if (!(original instanceof ServiceFunction)) return;
        ServiceFunction sf = (ServiceFunction) original;

        // Tao clone voi cung MIPS - framework tu chia traffic
        ServiceFunction newSf = new ServiceFunction(
                SDNVm.getUniqueVmId(),
                sf.getUserId(),
                sf.getMips(),
                sf.getNumberOfPes(),
                sf.getRam(),
                sf.getBw(),
                sf.getSize(),
                sf.getVmm(),
                new CloudletSchedulerSpaceSharedMonitor(Configuration.TIME_OUT),
                sf.getStartTime(),
                Double.POSITIVE_INFINITY
        );
        newSf.setName(sf.getName() + "_bf_" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName());

        System.out.printf("%.2f: [BestFit] CLONE VNF %s: %s -> %s%n",
                CloudSim.clock(), sf.getName(), src.getName(), dst.getName());

        // addDuplicatedSF tu goi addExtraVm va redirect traffic
        // KHONG goi addExtraVm them
        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);
    }
}