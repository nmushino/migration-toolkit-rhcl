package com.redhat.migrationtoolkit.rhcl;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "com.redhat.migrationtoolkit.rhcl",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    // ── Layered architecture ──────────────────────────────────────────────────

    @ArchTest
    static final ArchRule layeredArchitecture_isRespected = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy("com.redhat.migrationtoolkit.rhcl.controller..")
            .layer("Service").definedBy("com.redhat.migrationtoolkit.rhcl.service..")
            .layer("Client").definedBy("com.redhat.migrationtoolkit.rhcl.client..")
            .layer("Model").definedBy("com.redhat.migrationtoolkit.rhcl.model..")
            .layer("Entity").definedBy("com.redhat.migrationtoolkit.rhcl.entity..")
            .layer("DTO").definedBy("com.redhat.migrationtoolkit.rhcl.dto..")
            .layer("Util").definedBy("com.redhat.migrationtoolkit.rhcl.util..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Client").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("DTO").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Util").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Model").mayOnlyBeAccessedByLayers("Controller", "Service", "Client");

    // ── Controller rules ──────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule controllers_havePathAnnotation = classes()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.controller..")
            .and().areNotInterfaces()
            .and().areNotAnnotations()
            .and().arePublic()
            .and().areNotMemberClasses()
            .should().beAnnotatedWith(jakarta.ws.rs.Path.class);

    @ArchTest
    static final ArchRule controllers_doNotAccessClientDirectly = noClasses()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.redhat.migrationtoolkit.rhcl.client..");

    @ArchTest
    static final ArchRule controllers_areInCorrectPackage = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("com.redhat.migrationtoolkit.rhcl.controller..");

    // ── Service rules ─────────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule services_haveApplicationScopedAnnotation = classes()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.service..")
            .and().areNotInterfaces()
            .and().areNotMemberClasses()
            .and().areNotEnums()
            .should().beAnnotatedWith(jakarta.enterprise.context.ApplicationScoped.class);

    @ArchTest
    static final ArchRule services_areInCorrectPackage = classes()
            .that().haveSimpleNameEndingWith("Service")
            .and().areTopLevelClasses()
            .should().resideInAPackage("com.redhat.migrationtoolkit.rhcl.service..")
            .orShould().resideInAPackage("com.redhat.migrationtoolkit.rhcl.model..");

    @ArchTest
    static final ArchRule services_doNotHavePathAnnotation = noClasses()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.service..")
            .should().beAnnotatedWith(jakarta.ws.rs.Path.class);

    // ── Entity rules ──────────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule entities_haveEntityAnnotation = classes()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.entity..")
            .and().areNotInterfaces()
            .should().beAnnotatedWith(jakarta.persistence.Entity.class);

    @ArchTest
    static final ArchRule entities_areInCorrectPackage = classes()
            .that().haveSimpleNameEndingWith("Entity")
            .should().resideInAPackage("com.redhat.migrationtoolkit.rhcl.entity..");

    @ArchTest
    static final ArchRule entities_doNotDependOnControllers = noClasses()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.entity..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.redhat.migrationtoolkit.rhcl.controller..");

    // ── Client rules ──────────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule clients_areInterfaces = classes()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.client..")
            .should().beInterfaces();

    @ArchTest
    static final ArchRule clients_areInCorrectPackage = classes()
            .that().haveSimpleNameEndingWith("Client")
            .and().areNotAnnotations()
            .should().resideInAPackage("com.redhat.migrationtoolkit.rhcl.client..");

    // ── Model rules ───────────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule models_doNotDependOnControllerOrService = noClasses()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.redhat.migrationtoolkit.rhcl.controller..",
                    "com.redhat.migrationtoolkit.rhcl.service.."
            );

    // ── Util rules ────────────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule utilClasses_doNotDependOnControllers = noClasses()
            .that().resideInAPackage("com.redhat.migrationtoolkit.rhcl.util..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.redhat.migrationtoolkit.rhcl.controller..");

    // ── General code quality rules ────────────────────────────────────────────

    @ArchTest
    static final ArchRule noClassesShouldUseSystemOutPrintln = noClasses()
            .should().callMethod(System.class, "out")
            .orShould().callMethod(System.class, "err");

    @ArchTest
    static final ArchRule noClassesShouldUseJavaUtilLogging = noClasses()
            .should().dependOnClassesThat()
            .resideInAPackage("java.util.logging..");
}
