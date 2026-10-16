# MinIO provisioner third-party notices

This program is distributed with pinned dependencies recorded in `go.mod` and `go.sum`.
The source and dependency lock files are included in the same plugin source release.

- `github.com/minio/madmin-go/v3 v3.0.109` is licensed under GNU AGPL-3.0-or-later. Its source is available at [the pinned upstream tag](https://github.com/minio/madmin-go/tree/v3.0.109). The provisioner is separately containerized, but that separation is not a legal conclusion about the obligations for distributing this combined release. Obtain an explicit product/legal decision before publication.
- `github.com/minio/minio-go/v7 v7.0.91` is licensed under Apache-2.0. Its source is available at [the pinned upstream tag](https://github.com/minio/minio-go/tree/v7.0.91).
- Transitive Go module licenses are identified by the module versions and `go.sum`; the release process must produce a complete machine-generated license inventory and include it with the signed release descriptor.

The community MinIO server has a separate AGPL-3.0 license and corresponding-source obligation documented in `../MINIO_BUILD.md`.
