FROM eclipse-temurin:21-jdk AS build

# mkdir + cd
WORKDIR /workspace

# Copy root pom and matching module
COPY pom.xml pom.xml
COPY services/matching services/matching

# Cài Maven vào image
# -pl services/matching —> chỉ build module matching
# -am (also-make) —> tự động build các module mà matching phụ thuộc (parent pom + exchange-common)
# -DskipTests —> bỏ qua test cho nhanh
# package —> tạo ra file .jar trong target/
RUN apt-get update -q && apt-get install -y -q maven && \
    mvn -pl services/matching -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/matching/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
