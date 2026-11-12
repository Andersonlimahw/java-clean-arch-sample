# ADR 0012 — Java 25 LTS

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The payments platform compiled and ran on Java 21. Java 25 is the current LTS. [Spring Boot 3.5.16](https://docs.spring.io/spring-boot/3.5/system-requirements.html) supports Java 17 through Java 25, so the runtime can move without a Spring Boot 4 upgrade.

The baseline in [spec 000](../../specs/000-master-plan.md) was Java 21. Moving that baseline to Java 25 was requested by Lemon and is approved via the micro1 expert review gate.

[ADR 0001](0001-modular-monolith-vertical-slices.md) still says the superseded trip-planner was Java 21. That sentence is history for `java-journey-lemon-lab`, not the payments runtime.

## Decision

Compile and run on Java 25. `java.version` is 25. The Spring Boot parent sets `maven.compiler.release` from that property. `.java-version` is the same value, and CI reads it with `java-version-file`. The Maven enforcer rule is `[${java.version},26)`, so the build accepts 25 and rejects 26.

The Dockerfile declares `ARG JAVA_MAJOR=25` before the first `FROM` and uses it in both stages: `maven:3.9-eclipse-temurin-${JAVA_MAJOR}-alpine` to build, `eclipse-temurin:${JAVA_MAJOR}-jre-alpine` to run. `JavaVersionPinsTest` fails `mvn verify` when the pom `java.version`, `.java-version`, and `ARG JAVA_MAJOR` are not the same string. Surefire passes the pom property as `maven.java.version`. A filtered `@java.version@` is the wrong source: Maven resource filtering prefers the JVM system property of that name.

Stay on Spring Boot 3.5.16. No application source change for this move.

## Consequences

- Bytecode is class-file major version 69. ArchUnit 1.4.2 already reads it. Spring Boot 3.5.16 manages Byte Buddy 1.17.8, Flyway 11.7.2, and the compiler, Surefire, and Failsafe plugins that this build uses. Lombok is not used.
- Since JDK 23, javac does not run annotation processors that sit only on the classpath. `spring-modulith-starter-core` brings `spring-modulith-apt` (`SpringModulithProcessor`), and that artifact brings `spring-boot-configuration-processor`. The compiler plugin lists both on `annotationProcessorPaths`, with versions taken from the Boot parent and the Modulith BOM, and sets `<proc>full</proc>`. That restores `target/classes/META-INF/spring-configuration-metadata.json` (`JwtProperties`, prefix `app.jwt`) and `target/generated-spring-modulith/javadoc.json`, which `ModularityTest`'s `Documenter` reads.
- `application.yml` enables virtual threads (`spring.threads.virtual.enabled: true`). PR #15 replaced the `synchronized` stampede guard in `PaymentCacheService.getOrLoad` with a per-key `ReentrantLock` held across the Redis re-read, the database load, and the Redis write. `ReentrantLock` does not pin a virtual thread. A search of `src` finds no `synchronized` method or block, so nothing in this tree holds a monitor across I/O. `-Djdk.tracePinnedThreads=full` from that PR is not on the Surefire or Failsafe command line: JDK 24 removed the option, and it does nothing on 25. `VirtualThreadsIT` asserts `Thread.isVirtual()` and does not read pin traces, so the JFR event `jdk.VirtualThreadPinned` is not wired in.
- Surefire and Failsafe pass `-javaagent` on the Mockito jar when `mvn verify` runs `dependency:properties` and then sets `mockito.agent.argLine`. That property defaults to empty, so a direct `surefire:test` or `failsafe:integration-test` still starts the JVM and falls back to Mockito self-attach.
- Image JVM flags (`UseContainerSupport`, `MaxRAMPercentage`, `ExitOnOutOfMemoryError`) are still accepted on JDK 25. The Compose healthcheck still calls BusyBox `wget` on the Alpine JRE image.
- JDK 24+ prints `sun.misc.Unsafe` deprecation warnings. They are warnings on Java 25, and this build does not pass `--sun-misc-unsafe-memory-access` to hide them. Image startup warns from the OpenTelemetry SDK's shaded jctools (`Unsafe.objectFieldOffset` in `opentelemetry-sdk-trace`). Tests warn from Netty 4.1.135 via Lettuce (`Unsafe.allocateMemory` in `netty-common`). Both need an OpenTelemetry or Netty upgrade before a JDK that turns those calls into errors. Tests also warn, and only the tests, that JNA 5.13.0 from Testcontainers calls `System::load` without native access.
- JDK 24 disables the `TLS_RSA_*` cipher suites by default. This service does not set a custom cipher list, so it follows that platform default.

## Alternatives

| Option | Why not |
|---|---|
| Stay on Java 21 until Spring Boot 4 | Boot 3.5.16 already runs on Java 25. The cache stampede guard is a `ReentrantLock`, so staying on 21 would not preserve a carrier pin. |
| Spring Boot 4 and Java 25 | Boot 4 is a framework major (Spring Framework 7). It is not required to compile or run on Java 25. |
| Ubuntu Jammy Temurin images | The runtime is Alpine. Jammy would replace `addgroup`/`adduser` and the BusyBox `wget` healthcheck with a different base OS. |
