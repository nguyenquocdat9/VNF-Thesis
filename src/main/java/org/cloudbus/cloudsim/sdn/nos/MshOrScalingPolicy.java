package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.sfc.ServiceFunctionChainPolicy;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class MshOrScalingPolicy {
    private static final double ALPHA     = 0.4;
    private static final double BETA      = 0.4;
    private static final double GAMMA     = 0.2;
    public  static final double THRESHOLD = 0.7;

    public enum ScalingType { NONE, VERTICAL, HORIZONTAL }

    public static class ScalingDecision {
        public ScalingType type;
        public SDNHost targetHost;
        public ScalingDecision(ScalingType t, SDNHost h) {
            this.type = t; this.targetHost = h;
        }
    }

    // ===== VNF CANDIDATE =====

    public static class VnfScalingCandidate {
        public SDNVm vnf;
        public SDNHost host;
        public int sfcCount;
        public double utilization;

        public VnfScalingCandidate(SDNVm vnf, SDNHost host,
                                   int sfcCount, double utilization) {
            this.vnf = vnf;
            this.host = host;
            this.sfcCount = sfcCount;
            this.utilization = utilization;
        }
    }

    // ===== BUOC 0: LAY DANH SACH VNF QUA TAI, SAP XEP THEO SO SFC =====

    /**
     * Lay danh sach VNF dang qua tai (util >= THRESHOLD).
     * Sap xep theo so SFC giam dan:
     * VNF tham gia nhieu SFC -> anh huong lon nhat -> uu tien scale truoc.
     */
    public List<VnfScalingCandidate> getOverloadedVnfsSorted(
            List<SDNHost> allHosts,
            Collection<ServiceFunctionChainPolicy> sfcPolicies) {

        List<VnfScalingCandidate> candidates = new ArrayList<>();

        for (Object h : allHosts) {
            SDNHost host = (SDNHost) h;
            for (Object vmObj : host.getVmList()) {
                SDNVm vm = (SDNVm) vmObj;

                // Chi xet VNF (co middleboxType), bo qua client/server
                if (vm.getMiddleboxType() == null) continue;

                double util = getVmUtilization(vm);
                if (util < THRESHOLD) continue;

                int sfcCount = countSFCsUsingVm(vm.getId(), sfcPolicies);
                candidates.add(new VnfScalingCandidate(vm, host, sfcCount, util));
            }
        }

        // Sap xep: nhieu SFC truoc, cung SFC thi util cao truoc
        candidates.sort((a, b) -> {
            if (b.sfcCount != a.sfcCount) return b.sfcCount - a.sfcCount;
            return Double.compare(b.utilization, a.utilization);
        });

        return candidates;
    }

    // ===== TIM HOST TOI UU =====

    /**
     * Tim host dich toi uu de clone VNF sang.
     * Ham P = alpha*Delay + beta*Load + gamma*Cost, chon host co P nho nhat.
     */
    public SDNHost findBestTargetHost(SDNHost src,
                                      List<SDNHost> allHosts,
                                      Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        return findBestTargetExcluding(src, allHosts, sfcPolicies, null);
    }

    /**
     * Tim host toi uu, loai tru cac host da duoc dung lam target trong cung 1 lan scale.
     * Dam bao moi VNF duoc clone vao mot host khac nhau.
     */
    public SDNHost findBestTargetExcluding(SDNHost src,
                                           List<SDNHost> allHosts,
                                           Collection<ServiceFunctionChainPolicy> sfcPolicies,
                                           Set<Integer> excludedHostIds) {

        SDNHost bestHost = null;
        double minP = Double.MAX_VALUE;

        for (Object h : allHosts) {
            SDNHost candidate = (SDNHost) h;
            if (candidate.getId() == src.getId()) continue;

            // Bo qua host da duoc dung lam target trong lan nay
            if (excludedHostIds != null &&
                    excludedHostIds.contains(candidate.getId())) continue;

            if (getCpuUtilization(candidate, sfcPolicies) >= THRESHOLD) continue;
            if (candidate.getAvailableMips() < 300) continue;

            double p = calculateP(src, candidate, sfcPolicies);
            if (p < minP) {
                minP = p;
                bestHost = candidate;
            }
        }

        return bestHost;
    }

    // ===== CPU UTILIZATION =====

    /**
     * Tinh CPU utilization cua 1 VM cu the.
     * Dung getMonitoredUtilizationCPU trong window 5 giay.
     */
    public double getVmUtilization(SDNVm vm) {
        return vm.getMonitoredUtilizationCPU(
                CloudSim.clock() - 5.0, CloudSim.clock());
    }

    /**
     * Tinh CPU utilization cua host, co tinh den VNF Sharing.
     * Khong cap o 1.0 de phan anh dung trang thai overload.
     */
    public double getCpuUtilization(SDNHost host,
                                    Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        if (host.getVmList().isEmpty()) return 0;

        double totalUsed = 0, totalAllocated = 0;

        for (Object vmObj : host.getVmList()) {
            SDNVm vm = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double baseUtil  = getVmUtilization(vm);

            // He so VNF Sharing: neu nhieu SFC dung chung VNF nay
            int sfcCount = countSFCsUsingVm(vm.getId(), sfcPolicies);
            double sharingFactor = sfcCount <= 1 ? 1.0
                    : 1.0 + (sfcCount - 1) * 0.5;

            // Khong cap o 1.0 de phan anh dung trang thai overload
            double adjustedUtil = baseUtil * sharingFactor;

            totalUsed      += adjustedUtil * allocated;
            totalAllocated += allocated;
        }

        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    /**
     * Fallback: tinh utilization khong co SFC info.
     */
    public double getCpuUtilization(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;
        double totalUsed = 0, totalAllocated = 0;
        for (Object vmObj : host.getVmList()) {
            SDNVm vm = (SDNVm) vmObj;
            double allocated = vm.getMips() * vm.getNumberOfPes();
            double used = getVmUtilization(vm);
            totalUsed      += used * allocated;
            totalAllocated += allocated;
        }
        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    // ===== HAM MUC TIEU P =====

    /**
     * P = alpha*Delay + beta*Load + gamma*Cost
     * Gia tri nho hon = host dich tot hon.
     */
    private double calculateP(SDNHost src, SDNHost dst,
                              Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        double hopCount = estimateHopCount(src, dst);
        double delay    = hopCount * 2.0;
        double load     = getCpuUtilization(dst, sfcPolicies);
        double cost     = hopCount * 0.1;
        return ALPHA * delay + BETA * load + GAMMA * cost;
    }

    /**
     * Uoc tinh hop count dua tren naming convention:
     * host-p{pod}-e{edge}-h{h}
     * split("-") -> ["host", "p0", "e0", "h0"]
     */
    private double estimateHopCount(SDNHost h1, SDNHost h2) {
        try {
            String[] n1 = h1.getName().split("-");
            String[] n2 = h2.getName().split("-");
            if (!n1[1].equals(n2[1])) return 6.0; // Khac Pod
            if (!n1[2].equals(n2[2])) return 4.0; // Cung Pod, khac Edge
            return 2.0;                             // Cung Edge
        } catch (Exception e) { return 4.0; }
    }

    // ===== HELPERS =====

    /**
     * Dem so SFC dang di qua VM co id = vmId.
     */
    public int countSFCsUsingVm(int vmId,
                                Collection<ServiceFunctionChainPolicy> sfcPolicies) {
        if (sfcPolicies == null) return 1;
        int count = 0;
        for (ServiceFunctionChainPolicy policy : sfcPolicies) {
            if (policy.isSFIncludedInChain(vmId)) count++;
        }
        return count;
    }
}