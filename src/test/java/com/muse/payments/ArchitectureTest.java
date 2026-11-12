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

    private static final String[] RESILIENCE_AND_HTTP_CLIENTS = {
            "io.github.resilience4j..",
            "org.springframework.web.client..",
            "org.springframework.web.reactive.function.client..",
            "org.springframework.http.client..",
            "java.net.http..",
            "okhttp3..",
            "org.apache.hc.client5..",
            "org.apache.http.."
    };

    @ArchTest
    static final ArchRule domain_should_not_depend_on_resilience4j_or_http_clients = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(RESILIENCE_AND_HTTP_CLIENTS)
            .as("Domain must not import Resilience4j or an HTTP client");

    @ArchTest
    static final ArchRule application_should_not_depend_on_resilience4j_or_http_clients = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(RESILIENCE_AND_HTTP_CLIENTS)
            .as("Application must not import Resilience4j or an HTTP client; resilience stays in infrastructure");

    @ArchTest
    static final ArchRule payments_application_should_not_depend_on_spring_security = noClasses()
            .that().resideInAPackage("com.muse.payments.payments.application..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
            .as("Payments application authorizes with the URL matcher and domain rules, not Spring Security");

    @ArchTest
    static final ArchRule application_should_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .as("Application must not import infrastructure adapters");
}
