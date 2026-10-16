# MinIO server source and build lock

This development build uses the last official Community source release that was available when this plugin was implemented. The official repository is archived and no longer maintained. Its latest release page still identifies `RELEASE.2025-10-15T17-29-55Z`, which fixes a service-account/session-policy privilege escalation; no newer official Community source release or post-tag security commit is available in the archived repository as of 2026-10-16.

| Input | Locked value |
|---|---|
| Source | `https://github.com/minio/minio` |
| Release tag | `RELEASE.2025-10-15T17-29-55Z` |
| Source commit | `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` |
| Source archive SHA-256 | `45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841` |
| Source Go directive/toolchain | Go `1.24.0` / `1.24.8` |
| Build toolchain | `golang:1.27.2-alpine` OCI index `sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d` |
| Runtime base | `alpine:3.23` OCI index `sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0` |
| Build target | `linux/$TARGETARCH`; the generated image digest is a release output and is intentionally not invented here |

The tagged source declares Go 1.24.8, but the build uses the currently patched Go 1.27.2 toolchain after verifying the upstream module and official Makefile build flags. The Dockerfile verifies both declarations, verifies the downloaded source archive checksum, and builds with upstream's `kqueue` tag and `CGO_ENABLED=0`. It runs the server as UID/GID `10001`, stores data only in `/data`, and exposes only the private S3 API port. It does not publish a console port.

Build for the host platform:

```powershell
docker build --file artio-plugins/minio-object-storage/minio-server.Dockerfile --tag artio-minio-server:RELEASE.2025-10-15T17-29-55Z .
docker image inspect artio-minio-server:RELEASE.2025-10-15T17-29-55Z --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

The runtime image contains the MinIO binary, which is licensed under AGPL-3.0. Any redistribution must include the exact corresponding source, license/notice material, build materials and applicable AGPL compliance offer. This source lock is not a security-support commitment or production certification. Community MinIO's archived status means production use requires an explicit product and legal risk decision; do not silently replace it with AIStor or an unverified image.
