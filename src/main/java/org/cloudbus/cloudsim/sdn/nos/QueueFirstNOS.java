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
 * QueueFirstNOS: Scale VNF có QUEUE DÀI NHẤT trước (queue-based greedy).
 *
 * Lý thuyết: "VNF nào đang có nhiều request chờ nhất thì cần scale nhất."
 * Nghe hợp lý nhưng bỏ qua SFC priority — queue dài ở VNF phục vụ SFC thấp
 * không quan trọng bằng queue ngắn hơn ở VNF phục vụ SFC priority cao.
 *
 * So sánh 3 thuật toán:
 *   MSH-OR     → C1+C2+C3: xét SFC priority + severity + MIPS efficiency
 *   WorstFirst → util cao nhất: xét CPU pressure
 *   QueueFirst → queue dài nhất: xét backlog pressure
 *
 * Tất cả đều có Vertical Scale — sự khác biệt chỉ ở cách CHỌN VNF nào scale trước.
 */
public class QueueFirstNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999905;
    private static final double MONITOR_INTERVAL = 30.0;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.85;
    private static final double TARGET_UTIL      = 0.8;

    private static class VnfHostPair {
        final SDNVm   vnf;
        final SDNHost host;
        final int     queueLength; // waiting cloudlets
        VnfHostPair(SDNVm vnf, SDNHost host, int queue) {
            this.vnf         = vnf;
            this.host        = host;
            this.queueLength = queue;
        }
    }

    private boolean      monitoringStarted = false;
    private Set<Integer> scaledVnfs        = null;


    @Override
    public void startEntity() {
        super.startEntity();

        Configuration.SFC_AUTOSCALE_ENABLE               = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM             = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM  = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM_VERTICAL    = false;
        Configuration.SFC_AUTOSCALE_ENABLE_BW             = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_BW  = false;

        monitoringStarted = false;
        scaledVnfs        = new HashSet<>();

        System.out.println("### QueueFirstNOS.startEntity() - Scale by longest queue first");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledVnfs == null) scaledVnfs = new HashSet<>();

        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runQueueFirstLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                MshOrNOS.printWqb("QueueFirst");
                MshOrNOS.printCis("QueueFirst");
            }
        } else {
            super.processEvent(ev);
        }
    }


    private void runQueueFirstLogic() {
        List<SDNHost>                          hosts       = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        MshOrNOS.evaluateSnapshots(sfcPolicies);

        // WQB: tich luy moi cycle
        MshOrNOS.logQueueLength("QueueFirst", hosts);

        // Thu thập VNF quá tải + queue length
        List<VnfHostPair> overloaded = new ArrayList<>();
        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util >= THRESHOLD) {
                    int queue = vm.getCloudletScheduler()
                            .getCloudletWaitingList().size();
                    overloaded.add(new VnfHostPair(vm, host, queue));
                }
            }
        }

        if (overloaded.isEmpty()) {
            return;
        }

        // SẮP XẾP theo queue length GIẢM DẦN — VNF có backlog lớn nhất trước
        overloaded.sort((a, b) -> Integer.compare(b.queueLength, a.queueLength));

        System.out.printf("%.2f: [QUEUE-FIRST] %d VNF(s) overloaded (by queue desc):%n",
                CloudSim.clock(), overloaded.size());
        for (VnfHostPair p : overloaded) {
            System.out.printf("       %-12s | queue=%d%n",
                    p.vnf.getName(), p.queueLength);
        }

        // Scale 1 VNF/cycle — VNF có queue dài nhất
        int scaledThisCycle = 0;
        for (VnfHostPair pair : overloaded) {
            if (scaledThisCycle >= 1) break;
            doVerticalScale(pair.vnf, pair.host, sfcPolicies);
            scaledThisCycle++;
        }
    }


    private void doVerticalScale(
            SDNVm vnf, SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return;

        ServiceFunction sf       = (ServiceFunction) vnf;
        double currentMips       = sf.getMips();
        double util              = sf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
        double initMips          = sf.getInitialMips();
        double totalDemand       = util * initMips;
        double requiredMips      = totalDemand / TARGET_UTIL;
        double delta             = requiredMips - currentMips;

        scaledVnfs.add(vnf.getId());

        System.out.printf("%.2f: [QF VERT] %s | util=%.3f | demand=%.1f | required=%.1f | delta=%.1f%n",
                CloudSim.clock(), vnf.getName(), util, totalDemand, requiredMips, delta);

        if (delta <= 0) {
            System.out.printf("       -> No scale needed%n");
            return;
        }

        double availMips = host.getAvailableMips();
        if (availMips < delta) {
            System.out.printf("       -> SKIP: insufficient MIPS (avail=%.1f < need=%.1f)%n",
                    availMips, delta);
            return;
        }

        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips      = Math.min(requiredMips, maxMipsPerPe);
        double actualDelta  = newMips - currentMips;

        // Snapshot TRUOC setMips() -- do C1/C2/C3 chinh xac (delay chua thay doi)
        MshOrNOS.computeProjectedImprovement(vnf, actualDelta, sfcPolicies);

        sf.setMips(newMips);

        System.out.printf("       -> VERTICAL SCALE | %.1f -> %.1f MIPS (delta=%.1f)%n",
                currentMips, newMips, actualDelta);
    }
}