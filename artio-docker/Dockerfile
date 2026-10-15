FROM gradle:9.8.1-jdk25@sha256:e4f9e3d037cc0c7d8fc42cd97edb976dfd19a5b7314212b88b6de3807d13cd47 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :artio-rest-api:bootJar :artio-rest-api:extensionControllerBootJar

FROM eclipse-temurin:25-jre@sha256:fd4b1c02c64eef378212e23656fc0b0caa41a357876fa0b60e51ceda94a3db64
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
