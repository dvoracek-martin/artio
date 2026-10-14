FROM grafana/grafana:11.2.2@sha256:d5133220d770aba5cb655147b619fa8770b90f41d8489a821d33b1cd34d16f89
USER root
RUN mkdir -p /var/lib/grafana && chown -R 10001:10001 /var/lib/grafana
COPY provisioning /etc/grafana/provisioning
COPY artio-overview.json /var/lib/grafana/dashboards/artio-overview.json
ENV GF_PATHS_DATA=/var/lib/grafana GF_PATHS_LOGS=/tmp/grafana/log GF_PATHS_PLUGINS=/tmp/grafana/plugins
USER 10001:10001
EXPOSE 3000
