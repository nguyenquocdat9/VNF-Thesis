package org.cloudbus.cloudsim.sdn.nos;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.Configuration;

/**
 * NoScaleNOS -- Baseline 0: khong scale gi ca.
 * Dung lam baseline de do chenh lech delay/timeout
 * truoc va sau khi co scaling algorithm.
 */
public class NoScaleNOS extends NetworkOperatingSystemSimple {

    private static final int    MONITOR_EVENT    = 999906;
    private static final double MONITOR_INTERVAL = 30.0;
    private static final double SIM_END_TIME     = 200.0;

    private boolean monitoringStarted = false;

    @Override
    public void startEntity() {
        super.startEntity();
        Configuration.SFC_AUTOSCALE_ENABLE               = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_VM = false;
        Configuration.SFC_AUTOSCALE_ENABLE_VM_VERTICAL   = false;
        Configuration.SFC_AUTOSCALE_ENABLE_BW            = false;
        Configuration.SFC_AUTOSCALE_ENABLE_SCALE_DOWN_BW = false;
        monitoringStarted = false;
        System.out.println("### NoScaleNOS started -- NO scaling, baseline only");
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (!monitoringStarted) {
            monitoringStarted = true;
            schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
        }
        if (ev.getTag() == MONITOR_EVENT) {
            // Chi log queue, khong scale gi ca
            PAVScalingNOS.logQueueLength("NoScale", getHostList());
            if (CloudSim.clock() < SIM_END_TIME) {
                schedule(getId(), MONITOR_INTERVAL, MONITOR_EVENT);
            } else {
                PAVScalingNOS.printWqb("NoScale");
                System.out.println("### NoScaleNOS finished");
            }
        } else {
            super.processEvent(ev);
        }
    }
}
