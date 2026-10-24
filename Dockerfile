FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/api/build/libs/*.jar app.jar
COPY config/rds-ha.security /app/rds-ha.security
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.properties=/app/rds-ha.security", "-jar", "app.jar"]
