FROM grafana/grafana:11.2.2@sha256:d5133220d770aba5cb655147b619fa8770b90f41d8489a821d33b1cd34d16f89
USER root
RUN mkdir -p /var/lib/grafana && chown -R 10001:10001 /var/lib/grafana
COPY provisioning /etc/grafana/provisioning
COPY artio-overview.json /var/lib/grafana/dashboards/artio-overview.json
ENV GF_PATHS_DATA=/var/lib/grafana GF_PATHS_LOGS=/tmp GF_PATHS_PLUGINS=/var/lib/grafana/plugins \
    GF_SERVER_ROOT_URL=%(protocol)s://%(domain)s/plugins/observability/ GF_SERVER_SERVE_FROM_SUB_PATH=true \
    GF_AUTH_ANONYMOUS_ENABLED=false GF_USERS_ALLOW_SIGN_UP=false GF_USERS_AUTO_ASSIGN_ORG=true \
    GF_USERS_AUTO_ASSIGN_ORG_ROLE=Viewer GF_AUTH_PROXY_ENABLED=true GF_AUTH_PROXY_HEADER_NAME=X-WEBAUTH-USER \
    GF_AUTH_PROXY_HEADER_PROPERTY=username GF_AUTH_PROXY_AUTO_SIGN_UP=true GF_AUTH_PROXY_SYNC_TTL=0 \
    GF_AUTH_PROXY_HEADERS=Email:X-WEBAUTH-EMAIL
USER 10001:10001
EXPOSE 3000
