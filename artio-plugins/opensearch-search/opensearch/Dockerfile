FROM opensearchproject/opensearch:3.9.0@sha256:adfa61f85025d06b4aeb562e7e74fde7e31c437039c93c3862c17e9acebd6c7c
COPY --chown=1000:1000 opensearch.yml /usr/share/opensearch/config/opensearch.yml
ENV OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m"
USER root
RUN mkdir -p /usr/share/opensearch/data && chown -R 10001:10001 /usr/share/opensearch
USER 10001:10001
EXPOSE 9200
