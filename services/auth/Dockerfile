# ─── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# 1. Parent POM — cache layer riêng để Maven resolve dependency nhanh hơn
COPY pom.xml .
COPY services/auth/pom.xml services/auth/pom.xml

# 2. Download dependencies (layer này chỉ rebuild khi pom đổi)
RUN mvn -pl services/auth -am dependency:go-offline -q

# 3. Copy source rồi build
COPY services/auth/src services/auth/src
RUN mvn -pl services/auth -am package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user
RUN addgroup -S exchange && adduser -S auth -G exchange

COPY --from=builder /workspace/services/auth/target/*.jar app.jar
RUN chown auth:exchange app.jar

USER auth

# JVM tuning cho container
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
