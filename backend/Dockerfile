# syntax=docker/dockerfile:1.7
#
# MỘT Dockerfile cho cả 5 service Spring Boot, chọn service bằng build arg:
#   docker build --build-arg SERVICE=order-service -t orderflow/order-service backend
#
# Vì sao một file chứ không phải năm: năm service cùng một kiểu (Maven multi-module,
# fat jar Spring Boot, JDK 21). Năm bản chép sẽ lệch nhau ngay lần sửa đầu tiên.
#
# Multi-stage: stage "build" có Maven + JDK (~500 MB), image cuối chỉ có JRE + jar.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Parent POM + module dùng chung + mã nguồn. Test không chạy ở đây: CI chạy riêng
# (`mvn verify` với Testcontainers), còn trong Docker build không có Docker để dựng container test.
COPY pom.xml .
COPY shared shared
COPY services services
ARG SERVICE
# Cache ~/.m2 giữa các lần build: sửa một dòng code không phải tải lại hết thư viện.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl services/${SERVICE} -am -DskipTests package \
 && cp services/${SERVICE}/target/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
# Không chạy bằng root trong container.
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar app.jar
# Heap theo giới hạn bộ nhớ của container, không theo RAM của máy.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
