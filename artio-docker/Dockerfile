FROM gradle:8.14.6-jdk21@sha256:566f76c0c946a2f68e10d4374d61f28ad8411f36f364a41a981ab46ded096d18 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :artio-rest-api:bootJar :artio-rest-api:extensionControllerBootJar

FROM eclipse-temurin:21-jre@sha256:ccbad9e7cb84c27092b12c40a4143e5733e050db9c3784d84f632dcf423fb2ef
WORKDIR /app
RUN export DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-get upgrade --yes --no-install-recommends \
    && rm -f /usr/bin/pebble \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/artio-rest-api/build/libs/artio-rest-api.jar app.jar
COPY --from=build /workspace/artio-rest-api/build/libs/artio-extension-controller.jar extension-controller.jar
COPY artio-docker/start-api.sh /app/start-api.sh
RUN chmod 0755 /app/start-api.sh
EXPOSE 8080
ENTRYPOINT ["/app/start-api.sh"]
