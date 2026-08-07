package com.ecommercehub.app;

import com.ecommercehub.app.backfill.ChannelBudgetRegistry;
import com.ecommercehub.app.internalscreen.InternalScreenController;
import com.ecommercehub.app.push.PushWindowScheduler;
import com.ecommercehub.app.reconcile.ReconcileScheduler;
import com.ecommercehub.app.returns.ReturnController;
import com.ecommercehub.app.security.AuthController;
import com.ecommercehub.dispatcher.DispatcherScheduler;
import com.ecommercehub.ingest.WebhookController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v5 Faz 5 §5.5: proves the split is real, not just documented — a bean marked
 * {@code @Profile("worker")} must not exist at all when only "api" is active, and vice
 * versa. {@link AbstractTestcontainersTest}'s shared base activates both profiles
 * (every other gate test wants the full bean set); these two nested classes each
 * override that with a single profile to get their own, separately-cached context.
 */
class ProfileSeparationGateTests {

    @Nested
    @SpringBootTest
    @ActiveProfiles(profiles = "api", inheritProfiles = false)
    @DisplayName("api alone")
    class ApiOnly extends AbstractTestcontainersTest {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("no sweeper or dispatcher bean exists when only api is active")
        void noSweepersExist() {
            assertThat(context.getBeanNamesForType(ReconcileScheduler.class)).isEmpty();
            assertThat(context.getBeanNamesForType(PushWindowScheduler.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ChannelBudgetRegistry.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DispatcherScheduler.class)).isEmpty();
        }

        @Test
        @DisplayName("webhook and dashboard controllers exist when api is active")
        void controllersExist() {
            assertThat(context.getBeanNamesForType(WebhookController.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(InternalScreenController.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(AuthController.class)).isNotEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles(profiles = "worker", inheritProfiles = false)
    @DisplayName("worker alone")
    class WorkerOnly extends AbstractTestcontainersTest {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("no business HTTP controller exists when only worker is active")
        void noControllersExist() {
            assertThat(context.getBeanNamesForType(WebhookController.class))
                    .withFailMessage("worker must not accept webhook traffic — that is api's job")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(InternalScreenController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ReturnController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AuthController.class)).isEmpty();
        }

        @Test
        @DisplayName("every sweeper and the dispatcher exist when worker is active")
        void sweepersExist() {
            assertThat(context.getBeanNamesForType(ReconcileScheduler.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(PushWindowScheduler.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(ChannelBudgetRegistry.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(DispatcherScheduler.class)).isNotEmpty();
        }
    }
}
