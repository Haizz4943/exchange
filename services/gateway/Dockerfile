# ─── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# 1. Parent POM + exchange-common POM + gateway POM — cache layer for dependency resolution
COPY pom.xml .
COPY exchange-common/pom.xml exchange-common/pom.xml
COPY services/gateway/pom.xml services/gateway/pom.xml

# 2. Download dependencies (layer rebuilt only when pom files change)
RUN mvn -pl services/gateway -am dependency:go-offline -q

# 3. Copy source and build
COPY exchange-common/src exchange-common/src
COPY services/gateway/src services/gateway/src
RUN mvn -pl services/gateway -am package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user
RUN addgroup -S exchange && adduser -S gateway -G exchange

COPY --from=builder /workspace/services/gateway/target/*.jar app.jar
RUN chown gateway:exchange app.jar

USER gateway

# JVM tuning for container
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -Dreactor.netty.ioWorkerCount=4"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
