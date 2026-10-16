# syntax=docker/dockerfile:1.7
FROM --platform=$BUILDPLATFORM golang:1.27.2-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
ARG TARGETARCH
WORKDIR /src
ADD --checksum=sha256:45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841 \
    https://codeload.github.com/minio/minio/tar.gz/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a /tmp/minio.tar.gz
RUN tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /src \
    && test "$(grep '^go ' go.mod | awk '{print $2}')" = "1.24.0" \
    && test "$(grep '^toolchain ' go.mod | awk '{print $2}')" = "go1.24.8"
RUN mkdir -p /out/data /out/usr/share/licenses/minio \
    && cp LICENSE NOTICE /out/usr/share/licenses/minio/ \
    && CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build \
        -buildvcs=false -tags kqueue -trimpath \
        -ldflags "-s -w -X github.com/minio/minio/cmd.Version=RELEASE.2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.CopyrightYear=2025 -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.CommitID=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a -X github.com/minio/minio/cmd.ShortCommitID=9e49d5e7a648" \
        -o /out/minio . \
    && chmod 0755 /out/minio

FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0
COPY --from=build /out/minio /usr/bin/minio
COPY --from=build /out/usr/share/licenses/minio /usr/share/licenses/minio
COPY artio-plugins/minio-object-storage/minio-server-entrypoint.sh /usr/local/bin/minio-entrypoint
RUN mkdir -p /data && chown 10001:10001 /data && chmod 0700 /data
ENV HOME=/data
ENV MINIO_ROOT_USER_FILE=/run/secrets/minio-root-user
ENV MINIO_ROOT_PASSWORD_FILE=/run/secrets/minio-root-password
USER 10001:10001
EXPOSE 9000
VOLUME ["/data"]
ENTRYPOINT ["/bin/sh", "/usr/local/bin/minio-entrypoint"]
CMD ["server", "/data", "--address", ":9000"]
