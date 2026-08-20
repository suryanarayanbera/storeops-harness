package com.cognizant.storeops.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.cognizant.storeops.shared.error.AppError;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The StoreOps architecture rules, as executable checks.
 *
 * <p>This class is the automated equivalent of the dependency analyser the capstone requires
 * ("depcruiser or equivalent - zero cross-module repository imports"). It runs inside
 * {@code mvn test}, so a boundary violation fails the build rather than waiting for review. These
 * are the deterministic hard gates a harness Evaluator can rely on: same code in, same verdict out.
 *
 * <p>Each test maps to one rule from the specification:
 *
 * <ol>
 *   <li>Module boundary - no cross-module repository imports
 *   <li>No circular imports between modules
 *   <li>Event bus only - no direct import of a consuming module
 *   <li>Read-only reports
 *   <li>Layer separation - Routes to Service to Repository, no skipping
 *   <li>Error contract - no raw throws in routes or services
 * </ol>
 */
class ModuleBoundaryTest {

    private static final String BASE = "com.cognizant.storeops";

    private static final String ACTIVITIES = BASE + ".activities";
    private static final String PROGRAMMES = BASE + ".programmes";
    private static final String STAFF = BASE + ".staff";
    private static final String ALERTS = BASE + ".alerts";
    private static final String REPORTS = BASE + ".reports";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    // ---------------------------------------------------------------- rule 1

    @Test
    @DisplayName("Rule 1: no module imports another module's repository layer")
    void noCrossModuleRepositoryImports() {
        for (final String module : new String[] {ACTIVITIES, PROGRAMMES, STAFF, ALERTS, REPORTS}) {
            final ArchRule rule = ArchRuleDefinition.noClasses()
                    .that().resideOutsideOfPackage(module + "..")
                    .should().dependOnClassesThat().resideInAPackage(module + ".repository..")
                    .because("cross-module reads must go through the target module's service layer, "
                            + "never its repository");
            rule.check(PRODUCTION_CLASSES);
        }
    }

    @Test
    @DisplayName("Rule 1b: only a module's own service layer touches its repository")
    void repositoriesAreReachedOnlyFromServices() {
        final ArchRule rule = ArchRuleDefinition.classes()
                .that().resideInAPackage(BASE + "..repository..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage(BASE + "..repository..", BASE + "..service..")
                .because("the repository layer is reachable from its module's service layer only");
        rule.check(PRODUCTION_CLASSES);
    }

    // ---------------------------------------------------------------- rule 2

    @Test
    @DisplayName("Rule 2: the five modules and shared are free of dependency cycles")
    void modulesAreFreeOfCycles() {
        final ArchRule rule = slices()
                .matching(BASE + ".(*)..")
                .should().beFreeOfCycles()
                .because("a circular module dependency makes the boundaries unenforceable");
        rule.check(PRODUCTION_CLASSES);
    }

    // ---------------------------------------------------------------- rule 3

    @Test
    @DisplayName("Rule 3: activities, programmes and staff never import alerts or reports")
    void sideEffectsCrossBoundariesOnlyViaTheEventBus() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(ACTIVITIES + "..", PROGRAMMES + "..", STAFF + "..")
                .should().dependOnClassesThat().resideInAnyPackage(ALERTS + "..", REPORTS + "..")
                .because("cross-module side effects must be raised via EventBus.publish(), not by "
                        + "importing NotificationService or ReportService");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Rule 3b: domain events carry no module types, so subscribers stay decoupled")
    void eventsDoNotLeakModuleTypes() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(BASE + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ACTIVITIES + "..", PROGRAMMES + "..", STAFF + "..", ALERTS + "..", REPORTS + "..")
                .because("shared code must not depend on any module; an event carrying a module type "
                        + "would drag that module into every subscriber");
        rule.check(PRODUCTION_CLASSES);
    }

    // ---------------------------------------------------------------- rule 4

    @Test
    @DisplayName("Rule 4: reports reads other modules through their service layer only")
    void reportsReadsThroughServicesOnly() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(REPORTS + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ACTIVITIES + ".repository..",
                        PROGRAMMES + ".repository..",
                        STAFF + ".repository..",
                        ALERTS + ".repository..")
                .because("the reports module aggregates data and never writes to another module");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Rule 4b: reports touches no other module's repository or routes layer")
    void reportsTouchesOnlyServiceAndDomainOfOtherModules() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(REPORTS + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ACTIVITIES + ".repository..", ACTIVITIES + ".routes..",
                        PROGRAMMES + ".repository..", PROGRAMMES + ".routes..",
                        STAFF + ".repository..", STAFF + ".routes..",
                        ALERTS + ".repository..", ALERTS + ".routes..")
                .because("reports may read another module's service layer and its domain types, and "
                        + "nothing else - a write into another module's store would break the "
                        + "read-only rule");
        rule.check(PRODUCTION_CLASSES);
    }

    // ---------------------------------------------------------------- rule 5

    @Test
    @DisplayName("Rule 5: Routes to Service to Repository, with no layer skipped")
    void layersAreRespected() {
        final ArchRule rule = layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Routes").definedBy(BASE + "..routes..")
                .layer("Service").definedBy(BASE + "..service..")
                .layer("Repository").definedBy(BASE + "..repository..")
                .layer("Listener").definedBy(BASE + "..listener..")

                .whereLayer("Routes").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Routes", "Service", "Listener")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Repository")
                .because("routes hold no business logic and repositories are reached only from services");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Rule 5b: no repository depends on Spring Web - repositories know nothing of HTTP")
    void repositoriesAreFreeOfHttpConcerns() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(BASE + "..repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
                .because("the repository layer is data access only; HTTP belongs in routes");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Rule 5c: routes never reach a repository directly")
    void routesDoNotReachRepositories() {
        final ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(BASE + "..routes..")
                .should().dependOnClassesThat().resideInAPackage(BASE + "..repository..")
                .because("routes must go through their module's service layer");
        rule.check(PRODUCTION_CLASSES);
    }

    // ---------------------------------------------------------------- rule 6

    @Test
    @DisplayName("Rule 6: no generic throws anywhere in production code")
    void noGenericExceptionsAreThrown() {
        NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
                .because("every error must be an AppError subtype carrying code, message and statusCode")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Rule 6b: the whole error vocabulary lives in one place")
    void everyAppErrorSubtypeLivesInSharedError() {
        final ArchRule rule = ArchRuleDefinition.classes()
                .that().areAssignableTo(AppError.class)
                .should().resideInAPackage(BASE + ".shared.error..")
                .because("modules must reuse the shared AppError hierarchy rather than growing "
                        + "private error types the error handler does not know how to render");
        rule.check(PRODUCTION_CLASSES);
    }
}
