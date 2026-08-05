package com.ecommercehub.app.retention;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RetentionScheduler {

    private final RawEventPartitionMaintenanceService partitionMaintenanceService;
    private final MotorRetentionService motorRetentionService;

    RetentionScheduler(RawEventPartitionMaintenanceService partitionMaintenanceService,
                        MotorRetentionService motorRetentionService) {
        this.partitionMaintenanceService = partitionMaintenanceService;
        this.motorRetentionService = motorRetentionService;
    }

    @Scheduled(cron = "${hub.retention.cron:0 30 2 * * *}")
    @SchedulerLock(name = "hub-retention-sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    void runRetentionSweep() {
        partitionMaintenanceService.ensureUpcomingPartitions();
        partitionMaintenanceService.dropExpiredPartitions();
        motorRetentionService.purgeCompletedTasks();
    }
}
