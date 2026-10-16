# MinIO server source and build lock

The last official Community release visible on 2026-10-16 is `RELEASE.2025-10-15T17-29-55Z`, which fixes a service-account/session-policy privilege escalation. The official repository was archived on 2026-04-25. Checking its advisories changes the release decision: this source is **not eligible for an approved/published plugin release**. `GHSA-9c4q-hq6p-c237` / CVE-2026-40344 reports an unauthenticated object-write/signature-verification bypass affecting all OSS versions through the final Community source snapshot and has no patched Community version. Its recommended fix is in the separately licensed AIStor distribution. `GHSA-hv4r-mvr4-25vw` reports a related unsigned-trailer authentication bypass; the advisory recommends rejecting `STREAMING-UNSIGNED-PAYLOAD-TRAILER` at an ingress proxy as a workaround. The current plugin does not include that compensating proxy, and a workaround would not substitute for a patched upstream release or clear all release risks. The multi-node-only `GHSA-xh8f-g2qw-gcm7` path traversal is not exercised by this plugin's single-node managed topology, but it reinforces that the archived line has no ongoing Community security maintenance. This is only a development/test build pending an owner decision on a maintained, legally usable provider distribution and verified mitigations.

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
| Provisioning admin SDK | `github.com/minio/madmin-go/v3 v3.0.109`, as required by the pinned server release |
| Provisioning S3 SDK | `github.com/minio/minio-go/v7 v7.0.91`, as required by the pinned server release |
| Provisioner Go dependency lock | `artio-plugins/minio-object-storage/provisioner/go.mod` and `go.sum` |

The tagged source declares Go 1.24.8, but the build uses the currently patched Go 1.27.2 toolchain after verifying the upstream module and official Makefile build flags. The Dockerfile verifies both declarations, verifies the downloaded source archive checksum, and builds with upstream's `kqueue` tag and `CGO_ENABLED=0`. It runs the server as UID/GID `10001`, stores data only in `/data`, and exposes only the private S3 API port. It does not publish a console port.

Build for the host platform:

```powershell
docker build --file artio-plugins/minio-object-storage/minio-server.Dockerfile --tag artio-minio-server:RELEASE.2025-10-15T17-29-55Z .
docker image inspect artio-minio-server:RELEASE.2025-10-15T17-29-55Z --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

The runtime image contains the MinIO binary, which is licensed under AGPL-3.0. The provisioner uses the AGPL-3.0-or-later MinIO admin SDK; the separate container boundary is not a legal conclusion about distributing the combined release. Any redistribution must include corresponding source, license/notice material, complete transitive dependency inventory, build materials and applicable AGPL compliance offer. This source lock is not a security-support commitment or production certification. Community MinIO's archived status, the unpatched high-severity advisories and AGPL obligations block approval of this image bundle. Do not silently replace it with AIStor or an unverified third-party image. A development-only test does not bypass mandatory vulnerability and license scans.
