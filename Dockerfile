FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/api/build/libs/*.jar app.jar
# JVM이 읽을 DNS 캐시 보안 속성 파일을 실행 이미지에 포함한다.
COPY config/rds-ha.security /app/rds-ha.security
EXPOSE 8080
# 단일 = 옵션은 기본 java.security를 유지하면서 이 파일의 속성을 추가 적용한다.
ENTRYPOINT ["java", "-Djava.security.properties=/app/rds-ha.security", "-jar", "app.jar"]
