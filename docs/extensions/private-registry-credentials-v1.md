# Private OCI registry credentials (v1)

Core can pull digest-pinned extension images from explicitly trusted private OCI registries. Configure `ARTIO_EXTENSION_TRUSTED_REGISTRIES` as a comma-separated host allowlist (including a port when required), then manage credentials in Backoffice under Extensions. The registry host must match the allowlist; credentials do not establish trust for a manifest or release.

The API is rooted at `/api/v1/admin/extensions/registries`. `GET` lists host and username metadata, `PUT /{registryHost}` saves `{ "username": "...", "password": "..." }`, and `DELETE /{registryHost}` removes a credential. These routes require the existing extension-management authorization and an MFA-verified JWT issued within the last five minutes. Sign in again with an authenticator code before managing credentials when step-up is stale.

Core encrypts passwords with its configured encryption key before storing them in the current development schema table `extension_registry_credentials`. Passwords are excluded from list/update responses and audit details. The installation worker decrypts a credential only for a registry referenced by the frozen, digest-pinned manifest snapshot; in remote Docker mode it forwards the credential only over the authenticated Core-to-Extension-Controller request. The controller passes it to Docker's image pull operation and does not persist it.

Development keeps one current database schema in `V1__initial_schema.sql`. Credential schema edits belong in that snapshot; there is no development migration or upgrade chain.
