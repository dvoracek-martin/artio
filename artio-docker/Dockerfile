FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :artio-rest-api:bootJar :artio-rest-api:extensionControllerBootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/artio-rest-api/build/libs/artio-rest-api.jar app.jar
COPY --from=build /workspace/artio-rest-api/build/libs/artio-extension-controller.jar extension-controller.jar
COPY artio-docker/start-api.sh /app/start-api.sh
RUN chmod 0755 /app/start-api.sh
EXPOSE 8080
ENTRYPOINT ["/app/start-api.sh"]
