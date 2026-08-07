package com.ecommercehub.connector.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimitBudgetTests {

    @Test
    void splitsCapacityFiftyThirtyTwenty() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);

        assertThat(budget.remaining(BudgetClass.INTERACTIVE)).isEqualTo(50);
        assertThat(budget.remaining(BudgetClass.OPERATIONAL)).isEqualTo(30);
        assertThat(budget.remaining(BudgetClass.BACKGROUND)).isEqualTo(20);
    }

    @Test
    void exhaustingItsOwnShareAndHavingNoLowerClassToBorrowFromFailsForBackground() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        for (int i = 0; i < 20; i++) {
            assertThat(budget.tryAcquire(BudgetClass.BACKGROUND)).isTrue();
        }
        assertThat(budget.tryAcquire(BudgetClass.BACKGROUND))
                .withFailMessage("BACKGROUND has no lower-priority class to borrow idle capacity from")
                .isFalse();
    }

    @Test
    void interactiveBorrowsFromTheNearestLowerClassWithIdleCapacity() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        for (int i = 0; i < 50; i++) {
            assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE)).isTrue();
        }
        // INTERACTIVE's own 50 are gone; OPERATIONAL's 30 are still fully idle and closer in priority than BACKGROUND.
        assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE))
                .withFailMessage("Idle OPERATIONAL capacity must flow up to INTERACTIVE")
                .isTrue();
        assertThat(budget.remaining(BudgetClass.OPERATIONAL)).isEqualTo(29);
        assertThat(budget.remaining(BudgetClass.BACKGROUND)).isEqualTo(20);
    }

    @Test
    void interactiveBorrowsFromBackgroundOnceOperationalIsAlsoExhausted() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        for (int i = 0; i < 50; i++) {
            budget.tryAcquire(BudgetClass.INTERACTIVE);
        }
        for (int i = 0; i < 30; i++) {
            budget.tryAcquire(BudgetClass.OPERATIONAL);
        }
        // Both INTERACTIVE's own share and its nearer OPERATIONAL fallback are gone.
        assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE))
                .withFailMessage("Idle BACKGROUND capacity must still flow up to INTERACTIVE as a last resort")
                .isTrue();
        assertThat(budget.remaining(BudgetClass.BACKGROUND)).isEqualTo(19);
    }

    @Test
    void operationalBorrowsOnlyFromBackgroundNeverFromInteractive() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        for (int i = 0; i < 30; i++) {
            assertThat(budget.tryAcquire(BudgetClass.OPERATIONAL)).isTrue();
        }
        for (int i = 0; i < 20; i++) {
            assertThat(budget.tryAcquire(BudgetClass.OPERATIONAL)).isTrue();
        }
        assertThat(budget.remaining(BudgetClass.BACKGROUND)).isZero();
        assertThat(budget.remaining(BudgetClass.INTERACTIVE))
                .withFailMessage("OPERATIONAL borrowing must never touch INTERACTIVE's share")
                .isEqualTo(50);
        assertThat(budget.tryAcquire(BudgetClass.OPERATIONAL)).isFalse();
    }

    @Test
    void idleInteractiveCapacityNeverFlowsDownToBackground_noReverseFlow() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        // INTERACTIVE's 50 sit completely idle.
        for (int i = 0; i < 20; i++) {
            assertThat(budget.tryAcquire(BudgetClass.BACKGROUND)).isTrue();
        }
        assertThat(budget.tryAcquire(BudgetClass.BACKGROUND))
                .withFailMessage("Plan §9: idle capacity never flows downwards — BACKGROUND may not "
                        + "dip into INTERACTIVE's unused tokens")
                .isFalse();
    }

    @Test
    void backgroundSelfBacksOffOn429WithoutAffectingOtherClasses() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);

        budget.reportRateLimited(BudgetClass.BACKGROUND, Duration.ofMinutes(5));

        assertThat(budget.tryAcquire(BudgetClass.BACKGROUND))
                .withFailMessage("BACKGROUND must refuse to acquire while backed off, even with tokens available")
                .isFalse();
        assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE)).isTrue();
        assertThat(budget.tryAcquire(BudgetClass.OPERATIONAL)).isTrue();
    }

    @Test
    void refillRestoresEveryClassToItsFullShare() {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);
        for (int i = 0; i < 50; i++) {
            budget.tryAcquire(BudgetClass.INTERACTIVE);
        }
        assertThat(budget.remaining(BudgetClass.INTERACTIVE)).isZero();

        budget.refill();

        assertThat(budget.remaining(BudgetClass.INTERACTIVE)).isEqualTo(50);
        assertThat(budget.remaining(BudgetClass.OPERATIONAL)).isEqualTo(30);
        assertThat(budget.remaining(BudgetClass.BACKGROUND)).isEqualTo(20);
    }
}
