# syntax=docker/dockerfile:1
ARG JAVA_MAJOR=25
FROM maven:3.9-eclipse-temurin-${JAVA_MAJOR}-alpine AS builder
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

RUN JAR_FILE=$(ls target/*.jar | grep -v '\.original$' | head -n 1) && \
    java -Djarmode=tools -jar "$JAR_FILE" extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:${JAVA_MAJOR}-jre-alpine AS runtime
RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 -G appgroup appuser
WORKDIR /app

COPY --from=builder --chown=appuser:appgroup /workspace/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/extracted/application/ ./

USER appuser:appgroup

ENV PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
