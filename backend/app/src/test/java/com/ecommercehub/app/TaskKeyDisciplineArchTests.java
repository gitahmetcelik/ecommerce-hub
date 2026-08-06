package com.ecommercehub.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Plan §1.1: tasks are only ever produced through a factory that accepts the value
 * object, and no String-taking overload is added. The engine's idempotency key is a global
 * UNIQUE column with no tenant namespace (Plan §1.1) — a task submitted with a raw,
 * hand-built key string bypasses the {@link com.ecommercehub.domain.vo.TaskKey}
 * value object's {org}:{type}:{businessKey} formatting entirely, and a typo or
 * missing segment there means the task silently collides with (or never collides
 * with, when it should) another organization's or task type's key.
 *
 * <p>The engine's own submission API, {@code GorevGonderici.gonder(...)}, only
 * exists to be called from the dispatcher, which is the one place that builds a
 * TaskKey before calling it. This rule keeps it that way.
 */
class TaskKeyDisciplineArchTests {

    private static JavaClasses appClasses;

    @BeforeAll
    static void importClasses() {
        appClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ecommercehub");
    }

    @Test
    void onlyTheDispatcherPackageMaySubmitTasksToTheEngine() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.ecommercehub.dispatcher..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.gorevplatformu.motorcekirdek.GorevGonderici")
                .because("only the dispatcher builds a TaskKey before submitting a task — "
                        + "every other caller must go through hub.work_batch instead of the engine directly");

        rule.check(appClasses);
    }
}
