FROM eclipse-temurin:21-jdk AS build

# mkdir + cd
WORKDIR /workspace

# Copy root pom and order module
COPY pom.xml pom.xml
COPY services/order services/order

# Cài Maven vào image
# -pl services/order —> chỉ build module order
# -am (also-make) —> tự động build các module mà order phụ thuộc (ở đây là parent pom)
# -DskipTests —> bỏ qua test cho nhanh
# package —> tạo ra file .jar trong target/
RUN apt-get update -q && apt-get install -y -q maven && \
    mvn -pl services/order -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/order/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
