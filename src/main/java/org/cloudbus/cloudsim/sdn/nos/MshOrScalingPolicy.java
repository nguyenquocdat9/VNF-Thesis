package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.monitor.MonitoringValues;
import java.util.List;

public class MshOrScalingPolicy {
    private static final double ALPHA     = 0.4;
    private static final double BETA      = 0.4;
    private static final double GAMMA     = 0.2;
    private static final double THRESHOLD = 0.7;

    public enum ScalingType { NONE, VERTICAL, HORIZONTAL, MIGRATE }

    public static class ScalingDecision {
        public ScalingType type;
        public SDNHost targetHost;
        public ScalingDecision(ScalingType t, SDNHost h) { this.type = t; this.targetHost = h; }
    }

    public ScalingDecision evaluate(SDNHost host, List<SDNHost> allHosts) {
        double utilization = getCpuUtilization(host);
        if (utilization < THRESHOLD) return new ScalingDecision(ScalingType.NONE, null);

        // Ưu tiên 1: Vertical Scaling - Kiểm tra lõi PE vật lý còn trống
        if (host.getNumberOfFreePes() > 0) {
            return new ScalingDecision(ScalingType.VERTICAL, host);
        }

        // Ưu tiên 2: Horizontal Scaling
        SDNHost bestHost = null;
        double minP = Double.MAX_VALUE;
        for (SDNHost neighbor : allHosts) {
            if (neighbor.getId() == host.getId()) continue;
            if (getCpuUtilization(neighbor) >= THRESHOLD) continue;
            double p = calculateP(host, neighbor);
            if (p < minP) { minP = p; bestHost = neighbor; }
        }

        // Ưu tiên 3: Migration
        if (bestHost == null) return new ScalingDecision(ScalingType.MIGRATE, null);
        return new ScalingDecision(ScalingType.HORIZONTAL, bestHost);
    }

    public double getCpuUtilization(SDNHost host) {
        if (host.getVmList().isEmpty()) return 0;

        double totalUsed = 0;
        double totalAllocated = 0;

        for (Object vmObj : host.getVmList()) {
            org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm vm =
                    (org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm) vmObj;

            double allocated = vm.getMips() * vm.getNumberOfPes();
            // Lấy utilization trong 5 giây gần nhất
            double used = vm.getMonitoredUtilizationCPU(
                    CloudSim.clock() - 5.0,
                    CloudSim.clock()
            );
            totalUsed += used * allocated;
            totalAllocated += allocated;
        }

        return totalAllocated > 0 ? totalUsed / totalAllocated : 0;
    }

    private double calculateP(SDNHost src, SDNHost dst) {
        double hopCount = estimateHopCount(src, dst);
        double delay    = hopCount * 2.0;
        double load     = getCpuUtilization(dst);
        double cost     = hopCount * 0.1;
        return ALPHA * delay + BETA * load + GAMMA * cost;
    }

    private double estimateHopCount(SDNHost h1, SDNHost h2) {
        String[] n1 = h1.getName().split("-"); // ví dụ: [host, p0, e0, h0]
        String[] n2 = h2.getName().split("-");
        if (!n1[1].equals(n2[1])) return 6.0; // Khác Pod
        if (!n1[2].equals(n2[2])) return 4.0; // Cùng Pod, khác Edge
        return 2.0; // Cùng Edge Switch
    }
}