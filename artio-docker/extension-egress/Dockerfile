FROM python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
WORKDIR /opt/artio-egress
COPY --chown=10001:10001 proxy.py /opt/artio-egress/proxy.py
USER 10001:10001
EXPOSE 3128
HEALTHCHECK --interval=15s --timeout=2s --start-period=3s --retries=3 CMD python -c "import socket; s=socket.create_connection(('127.0.0.1',3128),1); s.close()"
CMD ["python", "/opt/artio-egress/proxy.py"]
