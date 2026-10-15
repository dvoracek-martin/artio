FROM otel/opentelemetry-collector-contrib:0.162.0@sha256:39923a8e431bd1f57be82411999d389fcfe40857492e4365456d97a4c1f74be6
COPY otel-collector.yaml /etc/otelcol-contrib/config.yaml
USER 10001:10001
EXPOSE 4317 4318 9464 13133
ENTRYPOINT ["/otelcol-contrib", "--config=/etc/otelcol-contrib/config.yaml"]
