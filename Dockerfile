# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Keep Maven dependencies in a reusable BuildKit cache.
# This layer is invalidated only when pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2     mvn -q -DskipTests dependency:go-offline

# Source changes now only invalidate the compile/package layer.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2     mvn -q -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.63.0-noble

USER root
RUN apt-get update     && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build --chown=pwuser:pwuser /build/target/ih-crawler-1.0.0.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8090

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8090/actuator/health || exit 1

USER pwuser

ENTRYPOINT ["java","-jar","/app/app.jar"]
