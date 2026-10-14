# Backup and recovery for a single Artio installation

Artio stores commerce records in PostgreSQL and installation credentials in the `artio-installation-secrets` named volume. The secrets volume contains the database password, JWT signing secret, active AES-GCM encryption key, stable extension-authentication key and first-run setup token. Back up both; a database dump without the matching keys cannot decrypt saved plugin secrets or preserve plugin credentials.

Run commands from the repository root with the normal Compose project name (`artio`). Keep the database dump and secrets archive in encrypted, access-controlled storage outside the machine running Artio. Do not commit either archive.

## Create a backup

```powershell
New-Item -ItemType Directory -Force .\backup | Out-Null
$postgres = (docker compose ps -q postgres).Trim()
docker compose exec -T postgres pg_dump -U artio -d artio -Fc -f /tmp/artio-postgres.dump
docker cp "${postgres}:/tmp/artio-postgres.dump" .\backup\artio-postgres.dump
docker run --rm -v artio_artio-installation-secrets:/source:ro -v "${PWD}:/backup" alpine:3.20 `
  tar czf /backup/artio-installation-secrets.tar.gz -C /source .
```

The PostgreSQL custom-format dump is transaction-consistent. Back up the secret volume immediately after the dump, and retain both files as one recovery set. Keep multiple generations and test restores in an isolated Compose project before relying on a backup.

## Restore to the same Compose installation

Stop the API so it cannot write while the database is restored. Restore the exact matching secret volume first, then restore the database dump into the existing PostgreSQL volume:

```powershell
docker compose stop rest-api backoffice storefront
docker run --rm -v artio_artio-installation-secrets:/target -v "${PWD}:/backup" alpine:3.20 `
  sh -c 'tar xzf /backup/artio-installation-secrets.tar.gz -C /target && chmod 700 /target && chmod 600 /target/*'
$postgres = (docker compose ps -q postgres).Trim()
docker cp .\backup\artio-postgres.dump "${postgres}:/tmp/artio-postgres.dump"
docker compose exec -T postgres pg_restore -U artio -d artio --clean --if-exists /tmp/artio-postgres.dump
docker compose up -d
```

The command above assumes a matching database schema and named volumes. For recovery on a new host, create the installation using Compose first, stop the API, restore the secrets into the newly created `artio_artio-installation-secrets` volume, restore PostgreSQL, then start the stack. Verify readiness, setup status, login, and decryption of an existing plugin configuration before routing traffic.

## Key loss and rotation

If an AES encryption key is lost, PostgreSQL commerce data remains readable but values encrypted with that key cannot be recovered; restore the matching key from the same backup set or configure new provider credentials. The separate `extension-auth-key` must also be restored: changing it changes every plugin runtime credential.

New ciphertexts use `v2.<key-id>.<base64>` AES-256-GCM envelopes. Legacy unversioned ciphertext remains readable while its key is supplied. The active key id is the full 64-character lowercase SHA-256 digest of the UTF-8 key material. During rotation, Artio reads up to eight previous keys from a JSON file in the protected installation secret volume. The file maps each key id to its raw key material; `ARTIO_ENCRYPTION_KEYRING_FILE` contains only the file path. Never put raw key material in `.env`, send it to an Artio API endpoint or commit it to the repository.

To rotate safely:

1. Create a normal encrypted backup set and verify that the matching `extension-auth-key` is retained.
2. Generate a new random active AES key in the installation secrets volume. Keep the prior active key in a JSON file such as `/run/artio-secrets/encryption-keyring.json`, keyed by its derived id, and set `ARTIO_ENCRYPTION_KEYRING_FILE=/run/artio-secrets/encryption-keyring.json` for both API and event-worker. Restart them to load the new active key and prior keyring. Keep `extension-auth-key` unchanged.
3. As SUPERADMIN, call `POST /api/v1/admin/system/security/encryption/rotate`. The endpoint accepts no key material. It locks and rewraps every MFA secret and extension secret in one database transaction and appends an audit event. A failure rolls the transaction back. Its response reports the active key id and number of rewrapped values.
4. Verify that MFA authentication and configured plugin invocations still work, then take another database-and-secrets backup. Remove the prior AES key from the keyring and restart API/event-worker only after that verification. Retain the previous backup according to the normal recovery policy.

If the rotation request fails because a ciphertext cannot be decrypted, leave the previous key in the keyring, restore the matching backup if needed, and resolve the key mismatch before retrying. The endpoint is idempotent: values already encrypted by the active key are left unchanged. This flow does not rotate the stable plugin-authentication key.
