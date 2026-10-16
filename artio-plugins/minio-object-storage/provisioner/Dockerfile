# syntax=docker/dockerfile:1.7
FROM --platform=$BUILDPLATFORM golang:1.27.2-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
ARG TARGETOS
ARG TARGETARCH
ARG LIFECYCLE_STAGE=provision
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build \
    -mod=readonly -buildvcs=false -trimpath -ldflags="-s -w -X main.lifecycleStage=${LIFECYCLE_STAGE}" -o /out/minio-provisioner .

FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0
RUN addgroup -S -g 10001 artio && adduser -S -D -H -u 10001 -G artio artio \
    && mkdir -p /usr/share/licenses/artio-minio-provisioner \
    && chown -R 10001:10001 /usr/share/licenses/artio-minio-provisioner
COPY --from=build --chown=10001:10001 /out/minio-provisioner /usr/local/bin/minio-provisioner
COPY --chown=10001:10001 THIRD_PARTY_NOTICES.md /usr/share/licenses/artio-minio-provisioner/THIRD_PARTY_NOTICES.md
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/minio-provisioner"]
