package com.ecommercehub.dispatcher;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for the dispatcher and orphan sweeper. Guarded by the task
 * engine's own ShedLock table so only one instance runs this at a time — per
 * plan §2.3, that single-instance constraint is the real ceiling on
 * maxThroughput = organizationCount * perOrgPerCycle / cyclePeriod, not the SQL.
 *
 * <p>The plan describes dispatch as "a cron job inside the engine"; wiring it
 * through the engine's own scheduled-task registry instead of a plain
 * {@code @Scheduled} method is deferred until task-handlers (Faz 0c) exists to
 * host that registration — the methods here are independently callable so gate
 * tests exercise them deterministically without waiting on a timer either way.
 */
@Component
public class DispatcherScheduler {

    private final WorkBatchDispatcher dispatcher;
    private final OrphanSweeperService orphanSweeper;

    public DispatcherScheduler(WorkBatchDispatcher dispatcher, OrphanSweeperService orphanSweeper) {
        this.dispatcher = dispatcher;
        this.orphanSweeper = orphanSweeper;
    }

    @Scheduled(fixedDelayString = "${hub.dispatcher.cycle-period-ms:1000}")
    @SchedulerLock(name = "work-batch-dispatch-cycle", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT30S")
    public void runDispatchCycle() {
        dispatcher.dispatchCycle();
    }

    @Scheduled(fixedDelayString = "${hub.dispatcher.sweep-period-ms:5000}")
    @SchedulerLock(name = "work-batch-orphan-sweep", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT30S")
    public void runOrphanSweep() {
        orphanSweeper.closeCompletedRows();
        orphanSweeper.escalateStuckRows();
    }
}
