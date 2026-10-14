FROM otel/opentelemetry-collector-contrib:0.111.0@sha256:a2a52e43c1a80aa94120ad78c2db68780eb90e6d11c8db5b3ce2f6a0cc6b5029
COPY otel-collector.yaml /etc/otelcol-contrib/config.yaml
USER 10001:10001
EXPOSE 4317 4318 9464 13133
ENTRYPOINT ["/otelcol-contrib", "--config=/etc/otelcol-contrib/config.yaml"]
