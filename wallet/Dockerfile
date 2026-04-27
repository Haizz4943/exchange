FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace/app

# Copy root pom and wallet module
COPY pom.xml /workspace/pom.xml
COPY wallet /workspace/app

WORKDIR /workspace
RUN apt-get update -q && apt-get install -y -q maven && \
    mvn -pl wallet -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/wallet/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
