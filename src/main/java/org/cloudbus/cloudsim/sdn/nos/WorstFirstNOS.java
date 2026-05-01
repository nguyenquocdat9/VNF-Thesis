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

/**
 * WorstFirstNOS: Scale VNF bị quá tải NẶNG NHẤT trước (utilization cao nhất).
 *
 * Lý thuyết đằng sau: "VNF nào đang chịu áp lực CPU cao nhất thì cần scale nhất."
 * Nghe có vẻ hợp lý nhưng bỏ qua câu hỏi quan trọng hơn:
 * "VNF đó phục vụ những SFC nào và SFC đó quan trọng đến mức nào?"
 *
 * So sánh với MSH-OR:
 *   MSH-OR     → chọn VNF theo Priority Score (C1×C2×C3) — có xét SFC priority
 *   WorstFirst → chọn VNF theo utilization cao nhất — chỉ xét tài nguyên
 *
 * Scenario phân biệt:
 *   vnf_fw  (util=100%, 5 SFC, SFC1-5 priority cao)
 *   vnf_enc (util=100%, 2 SFC, SFC5-6 priority thấp hơn)
 *   → Nếu util bằng nhau, WorstFirst chọn tùy thứ tự duyệt (không ổn định)
 *   → MSH-OR chọn vnf_fw vì C1 cao hơn (5 SFC) và C2 cao hơn (SFC priority cao)
 */
public class WorstFirstNOS extends NetworkOperatingSystemSimple {

    // =========================================================
    // CONSTANTS
    // =========================================================

    private static final int    MONITOR_EVENT    = 999904; // khac 3 thuat toan khac
    private static final double MONITOR_INTERVAL = 30.0;
    private static final double SIM_END_TIME     = 200.0;
    private static final double THRESHOLD        = 0.85;   // khop voi MSH-OR
    private static final double TARGET_UTIL      = 0.8;


    // =========================================================
    // INNER CLASS
    // =========================================================

    private static class VnfHostPair {
        final SDNVm   vnf;
        final SDNHost host;
        final double  utilization;
        VnfHostPair(SDNVm vnf, SDNHost host, double util) {
            this.vnf         = vnf;
            this.host        = host;
            this.utilization = util;
        }
    }


    // =========================================================
    // STATE
    // =========================================================

    private boolean      monitoringStarted = false;
    private Set<Integer> scaledVnfs        = null;


    // =========================================================
    // LIFECYCLE
    // =========================================================

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

        System.out.println("### WorstFirstNOS.startEntity() - Scale by highest utilization first");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledVnfs == null) scaledVnfs = new HashSet<>();

        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runWorstFirstLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                MshOrNOS.printWqb("WorstFirst");
                MshOrNOS.printCis("WorstFirst");
            }
        } else {
            super.processEvent(ev);
        }
    }


    // =========================================================
    // MAIN LOGIC
    // =========================================================

    private void runWorstFirstLogic() {
        List<SDNHost>                          hosts       = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        MshOrNOS.evaluateSnapshots(sfcPolicies);

        // WQB: tich luy moi cycle
        MshOrNOS.logQueueLength("WorstFirst", hosts);

        // Bước 1: Thu thập VNF quá tải
        List<VnfHostPair> overloaded = new ArrayList<>();
        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                double util = vm.getMonitoredUtilizationCPU(
                        CloudSim.clock() - 15.0, CloudSim.clock());
                if (util >= THRESHOLD) {
                    overloaded.add(new VnfHostPair(vm, host, util));
                }
            }
        }

        if (overloaded.isEmpty()) {
            return;
        }

        // Bước 2: SẮP XẾP theo utilization GIẢM DẦN
        // Khác MSH-OR: chỉ xét CPU load, không xét SFC priority
        overloaded.sort((a, b) -> Double.compare(b.utilization, a.utilization));

        System.out.printf("%.2f: [WORST-FIRST] %d VNF(s) overloaded (by util desc):%n",
                CloudSim.clock(), overloaded.size());
        for (VnfHostPair p : overloaded) {
            System.out.printf("       %-12s | util=%.3f%n", p.vnf.getName(), p.utilization);
        }

        // Bước 3: Scale 1 VNF/cycle — VNF có util cao nhất
        int scaledThisCycle = 0;
        for (VnfHostPair pair : overloaded) {
            if (scaledThisCycle >= 1) break;
            SDNVm   vnf  = pair.vnf;
            SDNHost host = pair.host;

            if (!scaledVnfs.contains(vnf.getId())) {
                doVerticalScale(vnf, host);
                scaledThisCycle++;
            }
        }
    }


    // =========================================================
    // VERTICAL SCALE
    // =========================================================

    private void doVerticalScale(SDNVm vnf, SDNHost host) {
        if (!(vnf instanceof ServiceFunction)) return;

        ServiceFunction sf       = (ServiceFunction) vnf;
        double currentMips       = sf.getMips();
        double util              = sf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
        double initMips          = sf.getInitialMips();
        double totalDemand       = util * initMips;
        double requiredMips      = totalDemand / TARGET_UTIL;
        double delta             = requiredMips - currentMips;

        System.out.printf("%.2f: [WF VERT] %s | util=%.3f | demand=%.1f | required=%.1f | delta=%.1f%n",
                CloudSim.clock(), vnf.getName(), util, totalDemand, requiredMips, delta);

        if (delta <= 0) {
            System.out.printf("       -> No scale needed%n");
            return;
        }

        double availMips = host.getAvailableMips();
        if (availMips < delta) {
            System.out.printf("       -> SKIP: insufficient host MIPS (avail=%.1f < delta=%.1f)%n",
                    availMips, delta);
            return;
        }

        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips      = Math.min(requiredMips, maxMipsPerPe);
        double actualDelta  = newMips - currentMips;

        // Snapshot TRUOC setMips() -- do C1/C2/C3 chinh xac (delay chua thay doi)
        MshOrNOS.computeProjectedImprovement(vnf, actualDelta, sfcForwarder.getAllPolicies());

        sf.setMips(newMips);
        scaledVnfs.add(vnf.getId());

        System.out.printf("       -> VERTICAL SCALE | %.1f -> %.1f MIPS (delta=%.1f)%n",
                currentMips, newMips, actualDelta);
    }
}