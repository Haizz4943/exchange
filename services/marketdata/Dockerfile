FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy root POM and shared library first (layer cache)
COPY pom.xml .
COPY exchange-common ./exchange-common

# Copy service sources
COPY services/marketdata ./services/marketdata

RUN apk add --no-cache maven && \
    mvn install -pl exchange-common -am -DskipTests -q && \
    mvn package -pl services/marketdata -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /workspace/services/marketdata/target/market-data-service-*.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
