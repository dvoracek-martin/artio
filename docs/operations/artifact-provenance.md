# Build artifact provenance

Every successful push workflow packages the API runtime JARs and the built Backoffice and Storefront assets into one commit-named archive. A separate, least-privilege job creates a signed GitHub artifact attestation for that exact archive only after the build, integration, Docker lifecycle and vulnerability-scanning gates pass. The archive is retained as a workflow artifact for 30 days; pull-request builds do not receive OIDC or attestation write permissions and do not publish a distributable archive.

Download the `artio-runtime-<commit-sha>` artifact from the successful workflow run, extract the archive, and verify the downloaded archive before using its contents:

```sh
gh attestation verify artio-<commit-sha>.tar.gz --repo dvoracek-martin/artio
```

Verification requires GitHub CLI authentication and checks the archive digest, signer identity, source repository and workflow provenance. A successful verification establishes which source and workflow produced these API and frontend artifacts; it does not establish that third-party provider images have been published or audited under the same identity.

## Container image SBOMs

Every CI run generates and validates a CycloneDX SBOM for each of the 15 locally built Artio images. The `artio-image-sboms-<commit>` workflow artifact contains one SBOM per image and `image-inventory.tsv`, which maps each source commit and image tag to its local immutable image ID, SBOM filename, and SBOM SHA-256. The workflow retains this artifact for 30 days. Vulnerability scanning remains a separate gate; an SBOM is a package inventory and does not imply that an image passed the vulnerability policy.

The CI runner removes the exact commit-tagged images after SBOM generation. The inventory therefore records the image IDs for traceability, but does not publish the OCI images or create image-digest attestations. Immutable registry publication and OCI image attestation remain release gates.

The workflow builds and scans local Docker images but does not publish them. Immutable public plugin image references and registry-backed image attestations remain release gates until the release registry and publication credentials are configured.
