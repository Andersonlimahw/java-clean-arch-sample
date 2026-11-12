# ADR 0012 — Java 25 LTS

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The payments platform compiled and ran on Java 21. Java 25 is the current LTS. [Spring Boot 3.5.16](https://docs.spring.io/spring-boot/3.5/system-requirements.html) already supports Java 17 through Java 25, so the runtime can move without a Spring Boot 4 upgrade.

[ADR 0001](0001-modular-monolith-vertical-slices.md) still says the superseded trip-planner was Java 21. That sentence is history for `java-journey-lemon-lab`, not the payments runtime.

## Decision

Compile and run on Java 25. `java.version` and `maven.compiler.release` are 25. The Maven enforcer rule requires a JDK ≥ 25. Images stay on the same Alpine tags the Dockerfile already used, with the major version moved to 25: `eclipse-temurin:25-jdk-alpine` to build and `eclipse-temurin:25-jre-alpine` to run. CI uses Temurin 25.

Stay on Spring Boot 3.5.16. Do not change application code for this move.

## Consequences

- Bytecode is class-file major version 69. ArchUnit 1.4.2 already reads it. Spring Boot 3.5.16 manages Byte Buddy 1.17.8, Lombok 1.18.46, Flyway 11.7.2, and the compiler, Surefire, and Failsafe plugins that this build uses.
- JaCoCo is not configured, and Lombok is not a source dependency. Neither version is pinned here.
- Image JVM flags (`UseContainerSupport`, `MaxRAMPercentage`, `ExitOnOutOfMemoryError`) are still accepted on JDK 25. Layers, user, and the Compose healthcheck are unchanged.
- Merge this after in-flight feature branches, and update it from `main` before merge, so those branches are not rebased onto a new bytecode level mid-review.
