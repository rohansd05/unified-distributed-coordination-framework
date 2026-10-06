# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Install unzip so mvnw downloads and verifies the pinned .zip distribution rather than falling back to .tar.gz
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

# Copy wrapper and project descriptors first to cache dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Windows build contexts can lose the executable bit; ensure mvnw is executable
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Copy application source code
COPY src src

# Tests run locally before every push; skipping them here saves Render build minutes.
RUN ./mvnw -B -q -DskipTests package

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a dedicated non-root user for container security
RUN groupadd -r appgroup && useradd -r -g appgroup -u 10001 appuser

COPY --from=build --chown=appuser:appgroup /build/target/udcf-backend-0.1.0.jar /app/app.jar

USER 10001

# Handled via environment; default JVM flags tuned for Render free tier (512 MB RAM, docs/HANDOFF.md 6.10)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Xss512k"

# Documentation only; Render maps and exposes the external port dynamically via $PORT
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
