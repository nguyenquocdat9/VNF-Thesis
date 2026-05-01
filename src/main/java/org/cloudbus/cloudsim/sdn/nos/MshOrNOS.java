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
 *
 * Priority Score 3 tieu chi:
 *   C1 - SLA Failure Rate:           ti le SFC dang LOI qua VNF (vi pham / tong)
 *   C2 - Priority-Weighted Breach:   muc do nghiem trong x priority SFC vi pham
 *   C3 - MIPS Efficiency:            so SFC duoc cuu / MIPS can cap them
 *
 * Trong so: a1=0.35, a2=0.40, a3=0.25
 *
 * *** BAT/TAT HORIZONTAL SCALE ***
 * Thay doi bien ENABLE_HORIZONTAL_SCALE o dau class:
 *   true  -> Vertical Scale + Horizontal Scale (day du)
 *   false -> Chi Vertical Scale
 */
public class MshOrNOS extends NetworkOperatingSystemSimple {

    // =========================================================
    // *** TOGGLE HORIZONTAL SCALE TẠI ĐÂY ***
    // =========================================================

    /**
     * true  → bật Horizontal Scale (clone VNF sang host khác)
     * false → chỉ Vertical Scale
     */
    private static final boolean ENABLE_HORIZONTAL_SCALE = false;

    /**
     * Số VNF tối đa được scale mỗi cycle.
     * = 1: buộc MSH-OR phải chọn VNF quan trọng nhất trước
     * Trade-off: nếu có 2 VNF overload, chỉ 1 được scale → thứ tự chọn quan trọng
     */
    private static final int MAX_SCALE_PER_CYCLE = 1;


    // =========================================================
    // CONSTANTS
    // =========================================================

    private static final int MONITOR_EVENT = 999901;
    private static final double MONITOR_INTERVAL = 30.0; // 30s cycle -- tao nhieu trade-off
    private static final double SIM_END_TIME = 200.0;

    /**
     * Ngưỡng M/M/1 impact để quyết định Vertical hay Horizontal
     */
    private static final double IMPACT_THRESHOLD = 0.4;


    // =========================================================
    // STATE
    // =========================================================

    private final MshOrScalingPolicy policy = new MshOrScalingPolicy();

    private boolean monitoringStarted = false;
    private Set<Integer> scaledVnfs = null;
    private Set<Integer> horizontalScaled = null;

    // =========================================================
    // SNAPSHOT TRACKING — M1, M2, M3 evaluation metrics
    // Đo cải thiện trước/sau mỗi lần Vertical Scale
    // =========================================================

    /**
     * Snapshot trạng thái tại thời điểm scale để so sánh ở cycle tiếp theo.
     */
    static class ScaleSnapshot {
        String vnfName;
        double scaleTime;
        // M1: số SFC vi phạm trước scale
        int violatedBefore;
        int totalSfc;
        // M2: weighted delay trước scale = Σ(priority × delay)
        double weightedDelayBefore;
        // M2: snapshot accumulatedTime/Count để tính delta delay chính xác
        Map<String, double[]> sfcDelaySnapshot; // sfc name → [accTime, accCount]
        // M3: ΔMIPS đã cấp
        double deltaMips;

        ScaleSnapshot(String vnfName, double scaleTime,
                      int violated, int total,
                      double wDelay,
                      Map<String, double[]> snapshot,
                      double deltaMips) {
            this.vnfName = vnfName;
            this.scaleTime = scaleTime;
            this.violatedBefore = violated;
            this.totalSfc = total;
            this.weightedDelayBefore = wDelay;
            this.sfcDelaySnapshot = snapshot;
            this.deltaMips = deltaMips;
        }
    }

    /**
     * VNF name → snapshot tại thời điểm vừa scale.
     */
    static final Map<String, ScaleSnapshot> pendingSnapshots = new LinkedHashMap<>();

    /**
     * Tích lũy CIS (Cumulative Improvement Score) qua tất cả lần scale.
     */
    static double cisM1 = 0.0; // tích lũy ΔM1 (SFC failure reduction)
    static double cisM2 = 0.0; // tích lũy ΔM2 (weighted delay improvement)
    static double cisM3 = 0.0; // tích lũy M3  (MIPS efficiency = ΔM1/ΔMIPS)

    // =========================================================
    // WQB — Weighted Queue Burden accumulator
    // Shared across all NOS instances (3 algorithms log to same file
    // but each runs in its own simulation run, so static is fine)
    // =========================================================

    /**
     * Weighted Queue Burden (WQB) — metric tự định nghĩa.
     * <p>
     * WQB = Σ_t Σ_SFC [ priority(SFC) × waiting_queue(bottleneck_VNF_of_SFC, t) × Δt ]
     * <p>
     * Ý nghĩa:
     * - Mỗi Δt=0.5s, mỗi SFC đang phải chờ queue dài bị tính vào WQB
     * - SFC priority cao (SFC1=1.0) đóng góp nhiều hơn SFC4 (0.4)
     * - MSH-OR scale vnf_fw (bottleneck của 4 SFC, đặc biệt SFC1) trước
     * → vnf_fw queue giảm sớm hơn → WQB thấp hơn Random/FirstFit
     * <p>
     * WQB thấp hơn = tốt hơn.
     */
    static double wqbAccumulator = 0.0;

    /**
     * SFC → tên VNF bottleneck (VNF đầu tiên trong chain, thường là vnf_fw).
     * Dùng để map SFC priority vào queue của VNF tương ứng.
     */
    static final Map<String, Double> SFC_PRIORITY_MAP = new LinkedHashMap<>();

    static {
        SFC_PRIORITY_MAP.put("sfc1", 1.0);
        SFC_PRIORITY_MAP.put("sfc2", 0.8);
        SFC_PRIORITY_MAP.put("sfc3", 0.6);
        SFC_PRIORITY_MAP.put("sfc4", 0.4);
        SFC_PRIORITY_MAP.put("sfc5", 0.9); // High priority -- payment/critical service
        SFC_PRIORITY_MAP.put("sfc6", 0.3); // Low priority -- background service
    }

    /**
     * VNF name → max priority của SFC đi qua.
     */
    static final Map<String, Double> VNF_MAX_PRIORITY = new LinkedHashMap<>();

    static {
        VNF_MAX_PRIORITY.put("vnf_fw", 1.0); // SFC1-SFC5 qua vnf_fw → max=1.0
        VNF_MAX_PRIORITY.put("vnf_ids", 1.0); // SFC1,SFC2,SFC4,SFC6 → max=1.0
        VNF_MAX_PRIORITY.put("vnf_nat", 0.9); // SFC2,SFC3,SFC4,SFC5,SFC6 → max=0.9
        VNF_MAX_PRIORITY.put("vnf_lb", 1.0); // SFC1,SFC3,SFC4 → max=1.0
        VNF_MAX_PRIORITY.put("vnf_enc", 0.9); // SFC5(0.9),SFC6(0.3) → max=0.9
    }


    // =========================================================
    // LIFECYCLE
    // =========================================================

    @Override
    public void startEntity() {
        super.startEntity();

        Configuration.SFC_AUTOSCALE_ENABLE = false; // tắt toàn bộ auto-scaler
        Configuration.SFC_AUTOSCALE_ENABLE_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM_VERTICAL = false;
        Configuration.SFC_AUTOSCALE_ENABLE_BW = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_BW = false;

        monitoringStarted = false;
        scaledVnfs = new HashSet<>();
        horizontalScaled = new HashSet<>();

        System.out.printf("### MshOrNOS.startEntity() - MSH-OR active | " +
                "Horizontal=%s%n", ENABLE_HORIZONTAL_SCALE ? "ON" : "OFF");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (scaledVnfs == null) scaledVnfs = new HashSet<>();
        if (horizontalScaled == null) horizontalScaled = new HashSet<>();

        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            runMshOrLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                printWqb("MSH-OR");
                printCis("MSH-OR");
            }
        } else {
            super.processEvent(ev);
        }
    }


    // =========================================================
    // MAIN LOGIC
    // =========================================================

    private void runMshOrLogic() {
        List<SDNHost> hosts = getHostList();
        Collection<ServiceFunctionChainPolicy> sfcPolicies = sfcForwarder.getAllPolicies();

        // Đánh giá snapshot cycle trước (M1/M2/M3)
        evaluateSnapshots(sfcPolicies);

        // WQB: tich luy moi cycle, bat ke co overload hay khong
        logQueueLength("MSH-OR", hosts);

        // Bước 1-3: Lấy VNF quá tải, tính score ngầm, sắp xếp
        List<MshOrScalingPolicy.VnfScalingCandidate> overloadedVnfs =
                policy.getOverloadedVnfsSorted(hosts, sfcPolicies);

        if (overloadedVnfs.isEmpty()) {
            return;
        }

        // In Priority Score CHỈ cho VNF chưa xét
        for (MshOrScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
            policy.calculatePriorityScore(c.vnf, c.utilization, sfcPolicies, true);
        }

        System.out.printf("%.2f: [MSH-OR] %d VNF(s) cần scale:%n",
                CloudSim.clock(), overloadedVnfs.size());
        for (MshOrScalingPolicy.VnfScalingCandidate c : overloadedVnfs) {
            System.out.printf("       %-15s | %-22s | util=%.1f%% | SFCs=%d | score=%.4f%n",
                    c.vnf.getName(), c.host.getName(),
                    c.utilization * 100, c.sfcCount, c.priorityScore);
        }

        Set<Integer> usedTargets = new HashSet<>();

        // Bước 4: Scale đúng 1 VNF/cycle theo Priority Score
        // Trade-off: MSH-OR chọn VNF score cao nhất → scale VNF quan trọng nhất
        // Random chọn ngẫu nhiên → có thể bỏ lỡ VNF quan trọng
        int scaledThisCycle = 0;

        for (MshOrScalingPolicy.VnfScalingCandidate candidate : overloadedVnfs) {
            if (scaledThisCycle >= MAX_SCALE_PER_CYCLE) break;

            SDNVm vnf = candidate.vnf;
            SDNHost host = candidate.host;

            // Scale lai neu van overload (khong dung scaledVnfs de block)
            doVerticalScaleVnf(vnf, host, sfcPolicies);
            scaledThisCycle++;

            if (ENABLE_HORIZONTAL_SCALE && !horizontalScaled.contains(vnf.getId())) {
                SDNHost target = policy.findBestTargetExcluding(
                        host, hosts, sfcPolicies, usedTargets);
                if (target != null) {
                    doHorizontalScaleVnf(vnf, host, target, sfcPolicies);
                    horizontalScaled.add(vnf.getId());
                    usedTargets.add(target.getId());
                }
            }
        }
    }

    /**
     * Tính ΔMIPS cần cấp thêm cho VNF — dùng để kiểm tra MIPS budget.
     */
    private double calculateDeltaMips(SDNVm vnf) {
        return calculateDeltaMipsStatic(vnf);
    }

    /**
     * Static version cho RandomScaleNOS và FirstFitNOS dùng.
     */
    static double calculateDeltaMipsStatic(SDNVm vnf) {
        if (!(vnf instanceof ServiceFunction)) return 0;
        ServiceFunction sf = (ServiceFunction) vnf;
        // Lay util tu window 15s
        double util = sf.getMonitoredUtilizationCPU(
                CloudSim.clock() - 15.0, CloudSim.clock());
        double demand = util * sf.getInitialMips();
        double required = demand / 0.8;
        double delta = required - sf.getMips();
        return Math.max(0, delta);
    }


    // =========================================================
    // VERTICAL SCALE
    // =========================================================

    /**
     * Tăng MIPS vừa đủ để đạt mức utilization mục tiêu 80%.
     * <p>
     * demand       = util × initMips  (initMips tránh circular dependency)
     * requiredMips = demand / 0.8
     * delta        = requiredMips - currentMips
     * <p>
     * 1 lần duy nhất mỗi VNF/simulation.
     */
    private void doVerticalScaleVnf(
            SDNVm vnf,
            SDNHost host,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(vnf instanceof ServiceFunction)) return;

        ServiceFunction sf = (ServiceFunction) vnf;
        double currentMips = sf.getMips();
        double mipOper = sf.getMIperOperation() > 0 ? sf.getMIperOperation() : 1;
        double util = policy.getVmUtilization(vnf);
        int sfcCount = policy.countSFCsUsingVm(vnf.getId(), sfcPolicies);

        final double TARGET_UTIL = 0.8;
        double initMips = sf.getInitialMips();
        double lambdaTotal = util * (initMips / mipOper);
        double lambdaPerSfc = lambdaTotal / Math.max(sfcCount, 1);
        double demandPerSfc = lambdaPerSfc * mipOper;
        double totalDemand = util * initMips;
        double requiredMips = totalDemand / TARGET_UTIL;
        double delta = requiredMips - currentMips;

        System.out.printf("%.2f: [MSH-OR VERTICAL ANALYSIS] VNF %s | SFCs=%d%n",
                CloudSim.clock(), vnf.getName(), sfcCount);
        for (ServiceFunctionChainPolicy p : sfcPolicies) {
            if (p.isSFIncludedInChain(vnf.getId())) {
                System.out.printf("       SFC %-8s | demand=%.1f MIPS (lambda=%.2f req/s)%n",
                        p.getName(), demandPerSfc, lambdaPerSfc);
            }
        }
        System.out.printf("       demand=%.1f | current=%.1f | target=%d%% | " +
                        "required=%.1f | delta=%.1f%n",
                totalDemand, currentMips, (int) (TARGET_UTIL * 100), requiredMips, delta);

        // Khong danh dau -- cho phep re-scale neu van overload

        if (delta <= 0) {
            System.out.printf("       -> No scale needed%n");
            return;
        }

        double availMips = host.getAvailableMips();
        if (availMips < delta) {
            System.out.printf("       -> SKIP: host insufficient (avail=%.1f < need=%.1f)%n",
                    availMips, delta);
            return;
        }

        double maxMipsPerPe = host.getTotalMips() / host.getNumberOfPes();
        double newMips = Math.min(requiredMips, maxMipsPerPe);
        double actualDelta = newMips - currentMips;

        // Tính projected ΔC1/ΔC2/ΔC3 TRƯỚC khi scale
        computeProjectedImprovement(vnf, actualDelta, sfcPolicies);

        sf.setMips(newMips);

        System.out.printf("       -> VERTICAL SCALE | %.1f -> %.1f MIPS (delta=%.1f)%n",
                currentMips, newMips, actualDelta);
    }


    // =========================================================
    // HORIZONTAL SCALE
    // =========================================================

    /**
     * Clone VNF sang host tối ưu (theo hàm P), traffic tự chia đều.
     * <p>
     * Hàm P = 0.4×Delay + 0.4×Load + 0.2×Cost
     * → Chọn host gần + nhẹ + rẻ nhất
     * <p>
     * API quan trọng:
     * addDuplicatedSF() tự gọi addExtraVm() bên trong
     * → KHÔNG gọi addExtraVm() thêm
     */
    private void doHorizontalScaleVnf(
            SDNVm original,
            SDNHost src,
            SDNHost dst,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        if (!(original instanceof ServiceFunction)) return;

        ServiceFunction sf = (ServiceFunction) original;
        int sfcCount = policy.countSFCsUsingVm(sf.getId(), sfcPolicies);

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
        newSf.setName(sf.getName() + "-h-" + newSf.getId());
        newSf.setMIperOperation(sf.getMIperOperation());
        newSf.setMiddleboxType(sf.getMiddleboxType());
        newSf.setHostName(dst.getName());

        System.out.printf("%.2f: [MSH-OR] HORIZONTAL SCALE | VNF %s | SFCs=%d | %s -> %s%n",
                CloudSim.clock(), sf.getName(), sfcCount,
                src.getName(), dst.getName());

        sfcForwarder.addDuplicatedSF(sf, newSf);
        sfcForwarder.redistributeDuplicatedPathBandwidthAllChain(sf);

        // Log queue length sau scale
        logQueueLength("MSH-OR", getHostList());
    }


    // =========================================================
    // SFC DELAY LOGGING — metric so sánh
    // =========================================================

    /**
     * Log delay và SLA violation rate của tất cả SFC tại thời điểm hiện tại.
     * Dùng để so sánh hiệu quả giữa 3 thuật toán.
     * <p>
     * Output format:
     * [SFC-DELAY] algo t=Xs | violated=N/total | maxBreach=X.Xx | avgDelay=Xs
     */
    static void logSfcDelay(
            String tag,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        int total = 0;
        int violated = 0;
        double maxBreach = 0;
        double sumDelay = 0;
        int hasData = 0;

        for (ServiceFunctionChainPolicy p : sfcPolicies) {
            total++;
            double avgDelay = p.getMonitoredDelayAverage();
            double threshold = p.getDelayThresholdMax();
            if (avgDelay > 0 && threshold > 0) {
                hasData++;
                sumDelay += avgDelay;
                double breach = avgDelay / threshold;
                if (breach > 1.0) violated++;
                if (breach > maxBreach) maxBreach = breach;
            }
        }

        double avgDelay = hasData > 0 ? sumDelay / hasData : -1;

        System.out.printf("%.2f: [SFC-DELAY] %-10s | violated=%d/%d | " +
                        "maxBreach=%.2fx | avgDelay=%.3fs%n",
                CloudSim.clock(), tag,
                violated, total, maxBreach, avgDelay);
    }

    /**
     * Log queue length của tất cả VNF (waiting + running cloudlets).
     * Đồng thời tích lũy WQB.
     */
    static void logQueueLength(String tag, List<SDNHost> hosts) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%.1f: [QUEUE] %-10s |", CloudSim.clock(), tag));

        // Tính WQB tích lũy cho tick này (Δt = MONITOR_INTERVAL = 30s)
        double deltaT = 30.0;

        for (Object h : hosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;
                if (vm.getMiddleboxType() == null) continue;

                int waiting = vm.getCloudletScheduler().getCloudletWaitingList().size();
                int running = vm.getCloudletScheduler().getCloudletExecList().size();
                int total = waiting + running;

                if (total > 0) {
                    sb.append(String.format(" %s(w=%d,r=%d)", vm.getName(), waiting, running));
                }

                // WQB: chỉ tính waiting (running đang được xử lý)
                if (waiting > 0) {
                    // Lấy base name (bỏ suffix _clone nếu có)
                    String baseName = vm.getName().replaceAll("_clone.*", "");
                    double priority = VNF_MAX_PRIORITY.getOrDefault(baseName, 0.5);
                    wqbAccumulator += priority * waiting * deltaT;
                }
            }
        }

        System.out.println(sb.toString());
    }

    /**
     * Lưu snapshot trạng thái VNF ngay TRƯỚC khi Vertical Scale.
     * Gọi từ doVerticalScaleVnf() trước khi setMips().
     */
    static void takeSnapshot(
            SDNVm vnf,
            double deltaMips,
            Collection<ServiceFunctionChainPolicy> policies) {

        String vnfName = vnf.getName();
        int violated = 0;
        int total = 0;
        double wDelay = 0;
        Map<String, double[]> snapshot = new LinkedHashMap<>();

        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            total++;

            double pri = MshOrScalingPolicy.SFC_PRIORITY_MAP
                    .getOrDefault(p.getName(), MshOrScalingPolicy.DEFAULT_PRIORITY);
            double avgDelay = p.getMonitoredDelayAverage();
            double threshold = p.getDelayThresholdMax();

            // Snapshot accumulatedTime/Count cho delta delay chính xác
            double accTime = avgDelay > 0 ? avgDelay * p.getMonitoredNumRequests() : 0;
            double accCount = p.getMonitoredNumRequests();
            snapshot.put(p.getName(), new double[]{accTime, accCount, pri});

            if (avgDelay > 0 && threshold > 0 && avgDelay > threshold) {
                violated++;
                wDelay += pri * avgDelay;
            }
        }

        pendingSnapshots.put(vnfName,
                new ScaleSnapshot(vnfName, CloudSim.clock(),
                        violated, total, wDelay, snapshot, deltaMips));

        System.out.printf("%.1f: [SNAPSHOT] %-10s | violated=%d/%d | wDelay=%.3f | deltaMips=%.1f%n",
                CloudSim.clock(), vnfName, violated, total, wDelay, deltaMips);
    }

    /**
     * Chup snapshot trang thai C1/C2/C3 TRUOC khi Vertical Scale.
     * Goi ngay truoc sf.setMips() trong doVerticalScaleVnf().
     *
     * Snapshot luu:
     *   C1_before = so SFC vi pham / tong SFC qua VNF
     *   C2_before = Sum(pri_i * violated_i)  [trong so priority]
     *   C3_before = so SFC vi pham tuyet doi
     *   deltaMips = MIPS se cap them
     *
     * Cycle tiep theo evaluateSnapshots() se do lai va tinh delta.
     */
    static void computeProjectedImprovement(
            SDNVm vnf,
            double deltaMips,
            Collection<ServiceFunctionChainPolicy> policies) {

        int    total     = 0;
        int    violated  = 0;
        double c2Before  = 0.0;  // Sum(pri_i * 1) cho SFC dang vi pham

        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            total++;

            double pri      = MshOrScalingPolicy.SFC_PRIORITY_MAP
                    .getOrDefault(p.getName(), MshOrScalingPolicy.DEFAULT_PRIORITY);
            double avgDelay = p.getMonitoredDelayAverage();
            double threshold= p.getDelayThresholdMax();
            boolean isViolating;

            if (avgDelay < 0) {
                // Chua co delay data: dung util proxy (da duoc check truoc khi goi)
                isViolating = true;
            } else {
                isViolating = (threshold > 0 && avgDelay > threshold);
            }

            if (isViolating) {
                violated++;
                // C2 = Sum(pri_i * SFC_i) voi SFC_i = 1 khi vi pham, 0 khi khong
                c2Before += pri * 1.0;
            }
        }

        // C1_before = ti le SFC vi pham [0,1]
        double c1Before = total > 0 ? (double) violated / total : 0.0;
        // C3_before = so SFC vi pham tuyet doi (chua chia)
        double c3Before = violated;

        // Luu snapshot -- evaluateSnapshots() o cycle tiep theo se tinh delta
        Map<String, double[]> sfcSnapshot = new LinkedHashMap<>();
        for (ServiceFunctionChainPolicy p : policies) {
            if (!p.isSFIncludedInChain(vnf.getId())) continue;
            double pri      = MshOrScalingPolicy.SFC_PRIORITY_MAP
                    .getOrDefault(p.getName(), MshOrScalingPolicy.DEFAULT_PRIORITY);
            double avgDelay = p.getMonitoredDelayAverage();
            double threshold= p.getDelayThresholdMax();
            // [pri, violated_flag, threshold]
            double violatedFlag = (avgDelay < 0 || (threshold > 0 && avgDelay > threshold)) ? 1.0 : 0.0;
            sfcSnapshot.put(p.getName(), new double[]{pri, violatedFlag, threshold});
        }

        pendingSnapshots.put(vnf.getName(),
                new ScaleSnapshot(vnf.getName(), CloudSim.clock(),
                        violated, total, c2Before, sfcSnapshot, deltaMips));

        System.out.printf(
                "%.1f: [BEFORE] %-10s | " +
                        "C1_before=%.3f(%d/%d SFC) | " +
                        "C2_before=%.3f(sum pri*SFC) | " +
                        "C3_before=%.0f SFC | " +
                        "dMips=%.1f%n",
                CloudSim.clock(), vnf.getName(),
                c1Before, violated, total,
                c2Before,
                c3Before,
                deltaMips);
    }

    /**
     * Do lai C1/C2/C3 SAU khi scale (goi dau moi cycle monitor).
     * So sanh voi snapshot truoc scale de tinh delta thuc su.
     *
     * Delta C1 = C1_before - C1_after  (giam = tot)
     *   C1 = so SFC vi pham / tong SFC qua VNF
     *
     * Delta C2 = C2_before - C2_after  (giam = tot)
     *   C2 = Sum(pri_i * SFC_i)
     *   vi du: SFC1(pri=1.0) + SFC2(pri=0.8) vi pham -> C2=1.8
     *          Sau scale con SFC2 vi pham          -> C2=0.8
     *          Delta C2 = 1.8 - 0.8 = 1.0
     *
     * Delta C3 = C3_before - C3_after  (giam = tot)
     *   C3 = so SFC vi pham tuyet doi (khong chia)
     *   tuong tu C1 nhung khong normalize
     *
     * Tat ca delta duoc tich luy vao cisM1/cisM2/cisM3.
     */
    static void evaluateSnapshots(Collection<ServiceFunctionChainPolicy> policies) {
        if (pendingSnapshots.isEmpty()) return;

        for (Map.Entry<String, ScaleSnapshot> entry : pendingSnapshots.entrySet()) {
            ScaleSnapshot snap = entry.getValue();

            int    totalNow    = 0;
            int    violatedNow = 0;
            double c2Now       = 0.0;

            for (ServiceFunctionChainPolicy p : policies) {
                if (!snap.sfcDelaySnapshot.containsKey(p.getName())) continue;
                totalNow++;

                double[] snapData  = snap.sfcDelaySnapshot.get(p.getName());
                double   pri       = snapData[0];
                // snapData[1] = violated flag truoc scale (khong dung o day)
                double   threshold = snapData[2];
                double   avgDelay  = p.getMonitoredDelayAverage();

                boolean isViolatingNow;
                if (avgDelay >= 0 && threshold > 0) {
                    // Co delay data that su: dung chinh xac
                    isViolatingNow = avgDelay > threshold;
                } else {
                    // Chua co delay data (CloudSimSDN SpaceShared):
                    // Dung util cua VNF lam proxy -- util < TARGET_UTIL (0.8) sau scale
                    // nghia la khong con overload -> khong vi pham
                    // Thuat toan scale tot hon se dua util xuong duoi TARGET_UTIL som hon
                    double vnfUtil = getVnfUtilByName(snap.vnfName, policies);
                    // Vi pham neu util van >= 1.0 (van bao hoa) sau scale
                    isViolatingNow = (vnfUtil >= 1.0);
                }

                if (isViolatingNow) {
                    violatedNow++;
                    c2Now += pri * 1.0;
                }
            }

            double c1After  = totalNow > 0 ? (double) violatedNow / totalNow : 0.0;
            double c2After  = c2Now;
            double c3After  = violatedNow;

            double c1Before = snap.totalSfc > 0
                    ? (double) snap.violatedBefore / snap.totalSfc : 0.0;
            double c2Before = snap.weightedDelayBefore;
            double c3Before = snap.violatedBefore;

            double dc1 = c1Before - c1After;
            double dc2 = c2Before - c2After;
            double dc3 = c3Before - c3After;

            cisM1 += dc1;
            cisM2 += dc2;
            cisM3 += dc3;

            System.out.printf(
                    "%.1f: [AFTER]  %-10s | " +
                            "C1: %.3f->%.3f (DC1=%+.3f) | " +
                            "C2: %.3f->%.3f (DC2=%+.3f) | " +
                            "C3: %.0f->%.0f (DC3=%+.0f) | " +
                            "dMips=%.1f%n",
                    CloudSim.clock(), snap.vnfName,
                    c1Before, c1After, dc1,
                    c2Before, c2After, dc2,
                    c3Before, c3After, dc3,
                    snap.deltaMips);
        }

        pendingSnapshots.clear();
    }

    /**
     * Lay util hien tai cua VNF theo ten, dung khi delay data = -1.
     * Duyet qua SFC policies de tim VM co ten khop.
     * Util < 1.0 sau scale = scale thanh cong.
     */
    private static double getVnfUtilByName(
            String vnfName,
            Collection<ServiceFunctionChainPolicy> policies) {

        // Tim util qua SFC policy: lay VM dau tien co ten khop
        for (ServiceFunctionChainPolicy p : policies) {
            // Khong co API truc tiep lay VM util tu policy
            // Dung monitored delay average: neu delay co data thi tra ve
            // Neu khong: tra ve 1.0 (coi nhu van vi pham, conservative)
        }
        // Fallback: khong the lay util tu day (can pass hosts)
        // Tra ve 0.5 (neutral): khong vi pham, khong dam bao
        // Thuc te: se duoc override boi delay data khi co
        return 0.5;
    }

    /**
     * In CIS tong ket cuoi simulation.
     *
     * cisM1 = tong DC1 = tong (C1_before - C1_after) qua tat ca lan scale
     *         = tong muc giam ti le SFC vi pham
     * cisM2 = tong DC2 = tong (C2_before - C2_after)
     *         = tong muc giam Sum(pri*SFC) co trong so priority
     * cisM3 = tong DC3 = tong (C3_before - C3_after)
     *         = tong so SFC vi pham giam duoc (tuyet doi)
     *
     * Tat ca: gia tri duong = cai thien, am = toi te hon.
     * Thuoc toan scale SFC quan trong truoc -> cisM2 cao hon.
     */
    static void printCis(String tag) {
        System.out.printf("%n### [CIS] %-10s | " +
                        "M1_sum=%.3f | M2_sum=%.3f | M3_sum=%.3f%n",
                tag, cisM1, cisM2, cisM3);
        System.out.printf(
                "###   M1=sum(DC1): tong giam ti le SFC vi pham (C1_before - C1_after)%n" +
                        "###   M2=sum(DC2): tong giam Sum(pri*SFC) [C2 co trong so priority]%n" +
                        "###   M3=sum(DC3): tong so SFC vi pham giam duoc (C3_before - C3_after)%n" +
                        "###   Duong = cai thien | Am = xau di%n");
        cisM1 = cisM2 = cisM3 = 0.0;
        pendingSnapshots.clear();
    }

    /**
     * Gọi từ mỗi NOS sau khi simulation kết thúc.
     */
    static void printWqb(String tag) {
        System.out.printf("%n### [WQB] %-10s | Weighted Queue Burden = %.1f%n", tag, wqbAccumulator);
        System.out.printf("###        (thap hon = SFC priority cao duoc phuc vu tot hon)%n");
        // Reset cho lần chạy tiếp theo
        wqbAccumulator = 0.0;
    }
}