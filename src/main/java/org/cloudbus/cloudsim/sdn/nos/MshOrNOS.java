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
 * MSH-OR: Multi-criteria SFC-aware Horizontal/vertical Optimal Resource scaling.
 * <p>
 * Logic phan cap:
 * <p>
 *   1. Monitor moi MONITOR_INTERVAL giay
 * <p>
 *   2. Lay danh sach VNF qua tai, sap xep theo Priority Score
 * <p>
 *   3. Voi moi VNF: thu Vertical Scale truoc (chi 1 lan)
 * <p>
 *      - Neu impact M/M/1 du lon va host con tai nguyen -> Vertical
 * <p>
 *      - Khong du -> Horizontal (clone sang host toi uu theo ham P)
 */
public class MshOrNOS extends NetworkOperatingSystemSimple {

    // =========================================================
    // CONSTANTS
    // =========================================================

    /** Tag su kien monitor dinh ky */
    private static final int    MONITOR_EVENT    = 999901;

    /** Chu ky monitor (giay) */
    private static final double MONITOR_INTERVAL = 0.5;

    /** Thoi diem ket thuc simulation */
    private static final double SIM_END_TIME     = 200.0;

    /**
     * So lan Vertical Scale toi da moi host.
     * Thay theo y thay: chi scale 1 lan, sau do monitor tiep.
     * Neu van overload sau scale -> Horizontal Scale.
     */
    private static final int    MAX_VERTICAL     = 1;

    /** Nguong M/M/1 impact: neu total < nguong nay thi skip Vertical */
    private static final double IMPACT_THRESHOLD = 0.4;


    // =========================================================
    // STATE
    // =========================================================

    private final MshOrScalingPolicy policy = new MshOrScalingPolicy();

    private boolean               monitoringStarted  = false;
    private Map<Integer, Integer> verticalScaleCount = null;
    private Set<Integer>          horizontalScaled   = null;


    // =========================================================
    // LIFECYCLE
    // =========================================================

    @Override
    public void startEntity() {
        super.startEntity();

        // Tat VM AutoScaler cua framework de tranh conflict voi MSH-OR
        Configuration.SFC_AUTOSCALE_ENABLE_VM          = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;

        // Reset state moi lan chay
        monitoringStarted  = false;
        verticalScaleCount = new HashMap<>();
        horizontalScaled   = new HashSet<>();

        System.out.println("### MshOrNOS.startEntity() - VM AutoScaler disabled, MSH-OR active");
    }

    @Override
    public void processEvent(SimEvent ev) {
        // Null-safety: phong truong hop processEvent chay truoc startEntity
        if (verticalScaleCount == null) verticalScaleCount = new HashMap<>();
        if (horizontalScaled   == null) horizontalScaled   = new HashSet<>();

        // Schedule monitoring lan dau tien tu day (hosts da san sang)
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


    // =========================================================
    // MAIN LOGIC
    // =========================================================

    private void runMshOrLogic() {
        List<SDNHost>                       hosts       = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        // Buoc 1: Lay danh sach VNF qua tai, da sap xep theo Priority Score
        List<MshOrScalingPolicy.VnfScalingCandidate> overloadedVnfs =
                policy.getOverloadedVnfsSorted(hosts, sfcPolicies);

        if (!overloadedVnfs.isEmpty()) {
            System.out.printf("%.2f: [MSH-OR] %d overloaded VNF(s):%n",
                    CloudSim.clock(), overloadedVnfs.size());
            for (MshOrScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
                System.out.printf("       %-15s | %-22s | util=%.1f%% | SFCs=%d | score=%.4f%n",
                        c.vnf.getName(), c.host.getName(),
                        c.utilization * 100, c.sfcCount, c.priorityScore);
            }
        }

        // usedTargets: dam bao moi VNF clone vao host khac nhau trong 1 cycle
        Set<Integer> usedTargets = new HashSet<>();

        for (MshOrScalingPolicy.VnfScalingCandidate candidate : overloadedVnfs) {
            SDNVm   vnf  = candidate.vnf;
            SDNHost host = candidate.host;

            // Skip neu host nay da horizontal scale roi trong simulation nay
            if (horizontalScaled.contains(host.getId())) continue;

            int    vCount    = getVerticalScaleCount(host.getId());
            double availMips = host.getAvailableMips();
            double newMips   = Math.min(
                    vnf.getMips() * 1.2,
                    host.getTotalMips() / host.getNumberOfPes()
            );

            // Buoc 2: Kiem tra M/M/1 impact cua Vertical Scale
            double impact = estimateVerticalScaleImpact(vnf, newMips, sfcPolicies);

            boolean canVertical = vCount < MAX_VERTICAL
                    && availMips >= vnf.getMips() * 0.2
                    && impact > IMPACT_THRESHOLD;

            if (canVertical) {
                // Buoc 3a: Vertical Scale
                doVerticalScaleVnf(vnf, host, sfcPolicies);
                verticalScaleCount.put(host.getId(), vCount + 1);

            } else {
                // Buoc 3b: Horizontal Scale
                if (impact <= IMPACT_THRESHOLD) {
                    System.out.printf("%.2f: [MSH-OR] VERTICAL SKIP " +
                                    "- impact=%.4f < %.1f -> Horizontal%n",
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


    // =========================================================
    // M/M/1 IMPACT ANALYSIS
    // =========================================================

    /**
     * Uoc tinh muc do cai thien delay tong the neu tang MIPS cua VNF.
     * <p>
     * Mo hinh M/M/1: D = 1 / (mu - lambda)
     *   mu     = service rate = MIPS / mipOper
     *   lambda = arrival rate = util * mu
     * <p>
     * Impact = (D_before - D_after) * sfcCount
     *   -> Cang nhieu SFC di qua VNF thi impact cang cao
     *   -> Impact > IMPACT_THRESHOLD moi dang scale
     */
    private double estimateVerticalScaleImpact(
            SDNVm   vnf,
            double  newMips,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return 1.0;

        ServiceFunction sf     = (ServiceFunction) vnf;
        double          mipOper = sf.getMIperOperation();
        if (mipOper <= 0) mipOper = 1;

        double muBefore = vnf.getMips() / mipOper;
        double muAfter  = newMips     / mipOper;
        double util     = policy.getVmUtilization(vnf);
        double lambda   = util * muBefore;

        // Edge cases: tranh chia cho 0
        if (lambda <= 0)       return 1.0; // khong co traffic -> cho scale
        if (lambda >= muBefore) lambda = muBefore * 0.99;
        if (lambda >= muAfter)  return 0;  // scale van khong du -> skip

        double improvement = (1.0 / (muBefore - lambda))
                - (1.0 / (muAfter  - lambda));

        int    sfcCount   = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);
        double totalImpact = improvement * sfcCount;

        System.out.printf("%.2f: [MSH-OR IMPACT] VNF %-10s | " +
                        "mu: %.2f->%.2f | D_improve=%.4f | SFCs=%d | total=%.4f | %s%n",
                CloudSim.clock(), vnf.getName(),
                muBefore, muAfter, improvement, sfcCount, totalImpact,
                totalImpact > IMPACT_THRESHOLD ? "SCALE" : "SKIP");

        return totalImpact;
    }


    // =========================================================
    // VERTICAL SCALE
    // =========================================================

    /**
     * Tang MIPS cua VNF dung vua du de dat TARGET_UTIL (80%).
     * <p>
     * Cong thuc:
     *   lambda     = util * (MIPS_ORIGINAL / mipOper)  [dung initMips tranh circular]
     *   totalDemand = lambda * mipOper = util * MIPS_ORIGINAL
     *   requiredMips = totalDemand / TARGET_UTIL
     * <p>
     * Vi sao 80% chu khong phai 100%:
     *   - 100%: mot request burst nho cung gay overload lai ngay
     *   - 80%: co buffer 20% de hap thu burst traffic
     */
    private void doVerticalScaleVnf(
            SDNVm   vnf,
            SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return;

        ServiceFunction sf      = (ServiceFunction) vnf;
        double currentMips      = vnf.getMips();
        double mipOper          = sf.getMIperOperation() > 0 ? sf.getMIperOperation() : 1;
        double util             = policy.getVmUtilization(vnf);
        int    sfcCount         = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);

        final double TARGET_UTIL  = 0.8;
        double       mipsOriginal = sf.getInitialMips(); // tranh circular dependency
        double       lambdaTotal  = util * (mipsOriginal / mipOper);
        double       lambdaPerSfc = lambdaTotal / sfcCount;
        double       demandPerSfc = lambdaPerSfc * mipOper;
        double       totalDemand  = lambdaTotal * mipOper; // = util * mipsOriginal
        double       requiredMips = totalDemand / TARGET_UTIL;
        double       delta        = requiredMips - currentMips;

        // --- Log chi tiet ---
        System.out.printf("%.2f: [MSH-OR VERTICAL ANALYSIS] VNF %s | SFCs=%d%n",
                CloudSim.clock(), vnf.getName(), sfcCount);

        for (ServiceFunctionChainPolicy p : sfcPolicies) {
            if (p.isSFIncludedInChain(vnf.getId())) {
                System.out.printf("       SFC %-8s | demand=%.1f MIPS (lambda=%.2f req/s)%n",
                        p.getName(), demandPerSfc, lambdaPerSfc);
            }
        }

        System.out.printf("       Total demand=%.1f MIPS | current=%.1f | " +
                        "target=%d%% | requiredMips=%.1f | delta=+%.1f MIPS%n",
                totalDemand, currentMips, (int)(TARGET_UTIL * 100), requiredMips, delta);

        // Kiem tra 1: MIPS hien tai da du (delta <= 0)
        if (delta <= 0) {
            System.out.printf("       -> No scale needed (already sufficient)%n");
            return;
        }

        // Kiem tra 2: Host co du MIPS khong
        double availMips = host.getAvailableMips();
        if (availMips < delta) {
            System.out.printf("       -> Host %s insufficient " +
                            "(avail=%.1f < need=%.1f) -> force Horizontal%n",
                    host.getName(), availMips, delta);
            verticalScaleCount.put(host.getId(), MAX_VERTICAL);
            return;
        }

        // Scale dung vua: khong vuot gioi han PE cua host
        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips      = Math.min(requiredMips, maxMipsPerPe);

        vnf.setMips(newMips);

        int count = verticalScaleCount.getOrDefault(host.getId(), 0) + 1;
        verticalScaleCount.put(host.getId(), count);

        System.out.printf("       -> VERTICAL SCALE #%d | %.1f -> %.1f MIPS " +
                        "(target %d%% util)%n",
                count, currentMips, newMips, (int)(TARGET_UTIL * 100));
    }


    // =========================================================
    // HORIZONTAL SCALE (CLONE)
    // =========================================================

    /**
     * Clone VNF sang host dich, dung sfcForwarder de traffic tu dong chia.
     * <p>
     * API quan trong:
     *   addDuplicatedSF(sf, newSf):
     *     - Them newSf vao sfPool
     *     - Tu goi addExtraVm() de deploy clone
     *     - Round-robin traffic giua sf va newSf
     *     -> KHONG goi addExtraVm() them (se tao VM 2 lan -> bi remove)
     * <p>
     *   redistributeDuplicatedPathBandwidthAllChain(sf):
     *     - Chia deu BW cho tat ca SFC di qua VNF nay
     */
    private void doHorizontalScaleVnf(
            SDNVm   original,
            SDNHost src,
            SDNHost dst,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(original instanceof ServiceFunction)) return;

        ServiceFunction sf       = (ServiceFunction) original;
        int             sfcCount = policy.countSFCsUsingVm(sf.getId(), sfcPolicies);

        // Tao clone la ServiceFunction (framework chi nhan SF trong SFC chain)
        ServiceFunction newSf = new ServiceFunction(
                SDNVm.getUniqueVmId(),
                sf.getUserId(),
                sf.getMips(),           // cung MIPS voi VM goc
                sf.getNumberOfPes(),
                sf.getRam(),
                sf.getBw(),
                sf.getSize(),
                sf.getVmm(),
                new CloudletSchedulerSpaceSharedMonitor(Configuration.TIME_OUT),
                sf.getStartTime(),
                Double.POSITIVE_INFINITY
        );
        newSf.setName(sf.getName() + "-mshor-" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName()); // pin clone vao host dich

        System.out.printf("%.2f: [MSH-OR] HORIZONTAL SCALE | VNF %s | SFCs=%d | %s -> %s%n",
                CloudSim.clock(), sf.getName(), sfcCount,
                src.getName(), dst.getName());

        // Deploy va redirect traffic
        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);
    }


    // =========================================================
    // HELPERS
    // =========================================================

    public int getVerticalScaleCount(int hostId) {
        return verticalScaleCount.getOrDefault(hostId, 0);
    }
}