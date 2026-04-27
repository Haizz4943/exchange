FROM eclipse-temurin:21-jdk AS build

# mkdir + cd
WORKDIR /workspace

# Copy root pom and wallet module
COPY pom.xml pom.xml
COPY services/wallet services/wallet

# Cài Maven vào image
# -pl services/wallet —> chỉ build module wallet
# -am (also-make) —> tự động build các module mà wallet phụ thuộc (ở đây là parent pom)
# -DskipTests —> bỏ qua test cho nhanh
# package —> tạo ra file .jar trong target/
RUN apt-get update -q && apt-get install -y -q maven && \
    mvn -pl services/wallet -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/wallet/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
