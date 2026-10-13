FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :artio-rest-api:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/artio-rest-api/build/libs/artio-rest-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]