FROM opensearchproject/opensearch:2.17.1@sha256:1193b7c29c5d63028523728243cc4da047ac49f697a8f8105e5aeee2f89bcc4c
COPY --chown=1000:1000 opensearch.yml /usr/share/opensearch/config/opensearch.yml
ENV OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m"
USER root
RUN mkdir -p /usr/share/opensearch/data && chown -R 10001:10001 /usr/share/opensearch
USER 10001:10001
EXPOSE 9200
