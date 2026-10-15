# Build artifact provenance

Every successful push workflow packages the API runtime JARs and the built Backoffice and Storefront assets into one commit-named archive. A separate, least-privilege job creates a signed GitHub artifact attestation for that exact archive only after the build, integration, Docker lifecycle and vulnerability-scanning gates pass. The archive is retained as a workflow artifact for 30 days; pull-request builds do not receive OIDC or attestation write permissions and do not publish a distributable archive.

Download the `artio-runtime-<commit-sha>` artifact from the successful workflow run, extract the archive, and verify the downloaded archive before using its contents:

```sh
gh attestation verify artio-<commit-sha>.tar.gz --repo dvoracek-martin/artio
```

Verification requires GitHub CLI authentication and checks the archive digest, signer identity, source repository and workflow provenance. A successful verification establishes which source and workflow produced these API and frontend artifacts; it does not establish that third-party provider images have been published or audited under the same identity.

The workflow builds and scans local Docker images but does not publish them. Immutable public plugin image references and registry-backed image attestations remain release gates until the release registry and publication credentials are configured.
