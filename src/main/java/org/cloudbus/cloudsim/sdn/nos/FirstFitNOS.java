package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudletSchedulerSpaceSharedMonitor;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunction;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Baseline 1: First Fit Scaling
 *
 * Khi VNF quá tải (util > 70%):
 *   - Không có Vertical Scale
 *   - Không dùng Priority Score
 *   - Clone VNF sang HOST ĐẦU TIÊN tìm thấy còn tài nguyên
 *   - CHỈ scale 1 VNF mỗi cycle (nhất quán với MSH-OR và Random)
 *
 * Monitoring window: 15s (nhất quán với MSH-OR và Random)
 */
public class FirstFitNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999902;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.85;

    private boolean      monitoringStarted = false;
    private Set<Integer> scaledVnfs        = null; // vmId đã clone

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
        scaledVnfs        = new HashSet<>();
        System.out.println("### FirstFitNOS.startEntity() - state reset");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledVnfs == null) scaledVnfs = new HashSet<>();
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            runFirstFitLogic();
            if (CloudSim.clock() < SIM_END_TIME)
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            else
                MshOrNOS.printWqb("FirstFit");
            MshOrNOS.printCis("FirstFit");
        } else {
            super.processEvent(ev);
        }
    }

    private static final Random RANDOM_FF = new Random(123);

    private void runFirstFitLogic() {
        List<SDNHost> hosts = new ArrayList<>(getHostList());
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        // Đánh giá snapshot cycle trước (M1/M2/M3)
        MshOrNOS.evaluateSnapshots(sfcPolicies);

        // Log SLA violation rate định kỳ
        MshOrNOS.logQueueLength("FirstFit", getHostList());

        // Shuffle de FirstFit khong trung thu tu voi MSH-OR
        // Van la "first fit" — chon host dau tien du dieu kien
        // nhung thu tu VNF duoc xet la ngau nhien (khac MSH-OR dung Priority Score)
        Collections.shuffle(hosts, RANDOM_FF);

        // CHỈ scale 1 VNF mỗi cycle — nhất quán với MSH-OR và Random
        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            if (host.getVmList().isEmpty()) continue;

            for (Object vmObj : new ArrayList<>(host.getVmList())) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;
                if (scaledVnfs.contains(vm.getId())) continue;

                // Window 15s — nhất quán với MSH-OR và Random
                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util < THRESHOLD) continue;

                System.out.printf("%.2f: [FirstFit] VNF %s on %s | util=%.1f%%%n",
                        CloudSim.clock(), vm.getName(), host.getName(), util * 100);

                // Tim HOST ĐẦU TIÊN còn tài nguyên (First Fit — không tối ưu)
                SDNHost target = findFirstFitHost(host, hosts);
                if (target != null) {
                    doClone(vm, host, target);
                    scaledVnfs.add(vm.getId());
                } else {
                    System.out.printf("%.2f: [FirstFit] WARNING: No host for %s%n",
                            CloudSim.clock(), vm.getName());
                }
                // Scale 1 VNF xong → dừng cycle này
                return;
            }
        }
    }

    /**
     * Chọn host đầu tiên còn tài nguyên.
     * Không tính hop count, không dùng hàm P — đây là điểm yếu của FirstFit.
     */
    private SDNHost findFirstFitHost(SDNHost src, List<SDNHost> allHosts) {
        for (Object h : allHosts) {
            SDNHost candidate = (SDNHost) h;
            if (candidate.getId() == src.getId()) continue;
            if (candidate.getAvailableMips() < 300) continue;
            if (getHostUtil(candidate) >= THRESHOLD) continue;
            return candidate;
        }
        return null;
    }

    private double getHostUtil(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;
        for (Object vmObj : host.getVmList()) {
            SDNVm  vm        = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double used      = vm.getMonitoredUtilizationCPU(
                    CloudSim.clock() - 15.0, CloudSim.clock());
            totalUsed      += used * allocated;
            totalAllocated += allocated;
        }
        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    private void doClone(SDNVm original, SDNHost src, SDNHost dst) {
        if (!(original instanceof ServiceFunction)) return;
        ServiceFunction sf = (ServiceFunction) original;

        ServiceFunction newSf = new ServiceFunction(
                SDNVm.getUniqueVmId(), sf.getUserId(), sf.getMips(), sf.getNumberOfPes(),
                sf.getRam(), sf.getBw(), sf.getSize(), sf.getVmm(),
                new CloudletSchedulerSpaceSharedMonitor(Configuration.TIME_OUT),
                sf.getStartTime(), Double.POSITIVE_INFINITY);
        newSf.setName(sf.getName() + "_ff_" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName());

        System.out.printf("%.2f: [FirstFit] CLONE VNF %s: %s -> %s%n",
                CloudSim.clock(), sf.getName(), src.getName(), dst.getName());

        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);

        // Log SLA violation rate sau scale
        MshOrNOS.logQueueLength("FirstFit", getHostList());
    }
}