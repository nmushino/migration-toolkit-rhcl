package com.example.migrationtool.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.migrationtool");
    }

    // ── Layered architecture ──────────────────────────────────────────────────

    @Test
    void layeredArchitecture_isRespected() {
        ArchRule rule = layeredArchitecture()
                .consideringAllDependencies()
                .layer("Controller").definedBy("com.example.migrationtool.controller..")
                .layer("Service").definedBy("com.example.migrationtool.service..")
                .layer("Client").definedBy("com.example.migrationtool.client..")
                .layer("Model").definedBy("com.example.migrationtool.model..")
                .layer("Entity").definedBy("com.example.migrationtool.entity..")
                .layer("DTO").definedBy("com.example.migrationtool.dto..")
                .layer("Util").definedBy("com.example.migrationtool.util..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Client").mayOnlyBeAccessedByLayers("Service")
                .whereLayer("Entity").mayOnlyBeAccessedByLayers("Controller", "Service")
                .whereLayer("DTO").mayOnlyBeAccessedByLayers("Controller", "Service")
                .whereLayer("Util").mayOnlyBeAccessedByLayers("Controller", "Service")
                .whereLayer("Model").mayOnlyBeAccessedByLayers("Controller", "Service", "Client");

        rule.check(importedClasses);
    }

    // ── Controller rules ──────────────────────────────────────────────────────

    @Test
    void controllers_havePathAnnotation() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.example.migrationtool.controller..")
                .and().areNotInterfaces()
                .and().areNotAnnotations()
                .and().arePublic()
                .and().areNotMemberClasses()
                .should().beAnnotatedWith(jakarta.ws.rs.Path.class);

        rule.check(importedClasses);
    }

    @Test
    void controllers_doNotAccessClientDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.migrationtool.controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.example.migrationtool.client..");

        rule.check(importedClasses);
    }

    @Test
    void controllers_areInCorrectPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("com.example.migrationtool.controller..");

        rule.check(importedClasses);
    }

    // ── Service rules ─────────────────────────────────────────────────────────

    @Test
    void services_haveApplicationScopedAnnotation() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.example.migrationtool.service..")
                .and().areNotInterfaces()
                .and().areNotMemberClasses()
                .and().areNotEnums()
                .should().beAnnotatedWith(jakarta.enterprise.context.ApplicationScoped.class);

        rule.check(importedClasses);
    }

    @Test
    void services_areInCorrectPackage() {
        // ApiService is a domain model class — allow model package in addition to service package
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Service")
                .and().areTopLevelClasses()
                .should().resideInAPackage("com.example.migrationtool.service..")
                .orShould().resideInAPackage("com.example.migrationtool.model..");

        rule.check(importedClasses);
    }

    @Test
    void services_doNotHavePathAnnotation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.migrationtool.service..")
                .should().beAnnotatedWith(jakarta.ws.rs.Path.class);

        rule.check(importedClasses);
    }

    // ── Entity rules ──────────────────────────────────────────────────────────

    @Test
    void entities_haveEntityAnnotation() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.example.migrationtool.entity..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(jakarta.persistence.Entity.class);

        rule.check(importedClasses);
    }

    @Test
    void entities_areInCorrectPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Entity")
                .should().resideInAPackage("com.example.migrationtool.entity..");

        rule.check(importedClasses);
    }

    @Test
    void entities_doNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.migrationtool.entity..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.example.migrationtool.controller..");

        rule.check(importedClasses);
    }

    // ── Client rules ──────────────────────────────────────────────────────────

    @Test
    void clients_areInterfaces() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.example.migrationtool.client..")
                .should().beInterfaces();

        rule.check(importedClasses);
    }

    @Test
    void clients_areInCorrectPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Client")
                .and().areNotAnnotations()
                .should().resideInAPackage("com.example.migrationtool.client..");

        rule.check(importedClasses);
    }

    // ── Model rules ───────────────────────────────────────────────────────────

    @Test
    void models_doNotDependOnControllerOrService() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.migrationtool.model..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.example.migrationtool.controller..",
                        "com.example.migrationtool.service.."
                );

        rule.check(importedClasses);
    }

    // ── Util rules ────────────────────────────────────────────────────────────

    @Test
    void utilClasses_doNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.migrationtool.util..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.example.migrationtool.controller..");

        rule.check(importedClasses);
    }

    // ── General code quality rules ────────────────────────────────────────────

    @Test
    void noClassesShouldUseSystemOutPrintln() {
        ArchRule rule = noClasses()
                .should().callMethod(System.class, "out")
                .orShould().callMethod(System.class, "err");

        rule.check(importedClasses);
    }

    @Test
    void noClassesShouldUseJavaUtilLogging() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("java.util.logging..");

        rule.check(importedClasses);
    }
}
