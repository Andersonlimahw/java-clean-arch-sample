package com.muse.payments;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

@AnalyzeClasses(packages = "com.muse.payments", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .as("Dependency injection should be done via constructors, not field injection");

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring_or_jpa = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "com.muse.payments..domain.."
            )
            .as("Domain classes must be pure and depend only on JDK and domain packages");

    @ArchTest
    static final ArchRule domain_should_not_have_framework_annotations = noClasses()
            .that().resideInAPackage("..domain..")
            .should().beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("jakarta.persistence.Entity")
            .as("Domain classes must not be annotated with Spring or JPA annotations");

    @ArchTest
    static final ArchRule web_should_not_depend_on_hibernate = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("org.hibernate..")
            .as("Web adapters must not depend on Hibernate");

    @ArchTest
    static final ArchRule application_should_not_depend_on_web_or_infra_entities = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..web..",
                    "..infrastructure..OutboxEventEntity.."
            )
            .as("Application layer must not depend on web controllers or infrastructure entities");
}
