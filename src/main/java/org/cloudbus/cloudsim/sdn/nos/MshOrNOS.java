package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import java.util.List;

public class MshOrNOS extends NetworkOperatingSystemSimple {

    private final MshOrScalingPolicy policy = new MshOrScalingPolicy();
    private static final int    MONITOR_EVENT    = 999901;
    private static final double MONITOR_INTERVAL = 0.5;
    private static final double SIM_END_TIME     = 200.0;
    private boolean monitoringStarted = false;

    @Override
    public void startEntity() {
        super.startEntity();
        System.out.println("### MshOrNOS.startEntity() CALLED");
    }

    @Override
    public void processEvent(SimEvent ev) {
        // Schedule monitoring on first event received (simulation has started)
        if (!monitoringStarted) {
            monitoringStarted = true;
            System.out.println("### MshOrNOS: First event received, scheduling monitoring. tag=" + ev.getTag());
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }

        if (ev.getTag() == MONITOR_EVENT) {
            System.out.println("### MONITOR_EVENT received at " + CloudSim.clock());
            runMshOrLogic();
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            }
        } else {
            super.processEvent(ev);
        }
    }

    private void runMshOrLogic() {
        List<SDNHost> hosts = getHostList();

        for (SDNHost host : hosts) {
            if (host.getVmList().isEmpty()) continue;

            double util = policy.getCpuUtilization(host);

            if (util > 0.01) {
                System.out.printf("%.2f: [MSH-OR] Host %s | util=%.4f (%.1f%%)%n",
                        CloudSim.clock(), host.getName(), util, util * 100);
            }

            MshOrScalingPolicy.ScalingDecision decision = policy.evaluate(host, hosts);
            switch (decision.type) {
                case VERTICAL:   doVerticalScale(host);                        break;
                case HORIZONTAL: doHorizontalScale(host, decision.targetHost); break;
                case MIGRATE:    doMigrate(host);                              break;
                default: break;
            }
        }
    }

    /*private void doVerticalScale(SDNHost host) {
        if (!host.getVmList().isEmpty()) {
            Vm vnf = host.getVmList().get(0); // Lấy VNF đang quá tải
            int currentPes = vnf.getNumberOfPes();

            // Tăng thêm 1 PE cho VNF
            vnf.setNumberOfPes(currentPes + 1);

            System.out.printf("%.2f: [EXECUTED] Vertical Scale: VM %d increased to %d PEs on %s%n",
                    CloudSim.clock(), vnf.getId(), vnf.getNumberOfPes(), host.getName());
        }
    }

    private void doHorizontalScale(SDNHost src, SDNHost dst) {
        if (!src.getVmList().isEmpty()) {
            Vm vnfToScale = src.getVmList().get(0);

            // Thực hiện di trú Instance sang Host láng giềng tốt nhất
            processVmMigrate(vnfToScale, src, dst);

            System.out.printf("%.2f: [EXECUTED] Horizontal Scale: Moved Instance of VM #%d to %s%n",
                    CloudSim.clock(), vnfToScale.getId(), dst.getName());
        }
    }

    private void doMigrate(SDNHost host) {
        System.out.printf("%.2f: [MSH-OR] MIGRATE from %s (no neighbor)%n",
                CloudSim.clock(), host.getName());
        // TODO: gọi processVmMigrate() cho toàn bộ VMs trên host
    }*/

    private void doVerticalScale(SDNHost host) {
        System.out.printf("%.2f: [MSH-OR] VERTICAL SCALE on %s (util=%.1f%%)%n",
                CloudSim.clock(), host.getName(),
                policy.getCpuUtilization(host) * 100);
    }

    private void doHorizontalScale(SDNHost src, SDNHost dst) {
        System.out.printf("%.2f: [MSH-OR] HORIZONTAL SCALE %s -> %s%n",
                CloudSim.clock(), src.getName(), dst.getName());
    }

    private void doMigrate(SDNHost host) {
        System.out.printf("%.2f: [MSH-OR] MIGRATE from %s (no neighbor)%n",
                CloudSim.clock(), host.getName());
    }
}