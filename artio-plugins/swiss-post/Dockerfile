FROM python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080
WORKDIR /opt/artio-extension
RUN mkdir -p /data/state && chown -R 10001:10001 /data
COPY --chown=10001:10001 server.py /opt/artio-extension/server.py
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=2s --start-period=5s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=1)"
CMD ["python", "/opt/artio-extension/server.py"]
