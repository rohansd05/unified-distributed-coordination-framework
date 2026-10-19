# Stage 1: Build
# Plain Maven image, not ./mvnw: the wrapper's distribution checksum check fails inside Docker (seen in 9A.1).
# Same Maven version as .mvn/wrapper/maven-wrapper.properties (3.9.9).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the project descriptor first to cache dependencies
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy application source code
COPY src src

# Tests run in CI before every merge; skipping them here saves Render build minutes.
RUN mvn -B -DskipTests package

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a dedicated non-root user for container security
RUN groupadd -r appgroup && useradd -r -g appgroup -u 10001 appuser

COPY --from=build --chown=appuser:appgroup /build/target/udcf-backend-0.1.0.jar /app/app.jar

USER 10001

# JVM memory flags come from the JAVA_TOOL_OPTIONS environment variable, set on the host
# (Render: -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k; see docs/deploy/public-deploy.md).

# Documentation only; Render maps and exposes the external port dynamically via $PORT
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
