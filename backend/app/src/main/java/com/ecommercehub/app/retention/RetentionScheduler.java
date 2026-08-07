package com.ecommercehub.app.retention;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Plan v5 Faz 5: {@code @Profile("worker")} — see {@link com.ecommercehub.app.reconcile.ReconcileScheduler}'s javadoc. */
@Component
@Profile("worker")
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
