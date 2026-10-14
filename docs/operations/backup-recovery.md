# Backup and recovery for a single Artio installation

Artio stores commerce records in PostgreSQL, installation credentials in the `artio-installation-secrets` named volume, and product image blobs in the `artio-catalog-media` named volume. A complete recovery set must contain the database dump and both volumes. The secrets volume contains the database password, JWT signing secret, active AES-GCM encryption key, stable extension-authentication key and first-run setup token. A database dump without the matching keys cannot decrypt saved plugin secrets or preserve plugin credentials; a dump without the media volume leaves product image records with missing files.

Run commands from the repository root with the normal Compose project name (`artio`). Keep the database dump and both volume archives in encrypted, access-controlled storage outside the machine running Artio. Do not commit any backup artifact.

## Create a backup

```powershell
docker compose stop rest-api event-worker notification-worker
New-Item -ItemType Directory -Force .\backup | Out-Null
$postgres = (docker compose ps -q postgres).Trim()
docker compose exec -T postgres pg_dump -U artio -d artio -Fc -f /tmp/artio-postgres.dump
docker cp "${postgres}:/tmp/artio-postgres.dump" .\backup\artio-postgres.dump
docker run --rm -v artio_artio-installation-secrets:/source:ro -v "${PWD}/backup:/backup" alpine:3.20 `
  tar czf /backup/artio-installation-secrets.tar.gz -C /source .
docker run --rm -v artio_artio-catalog-media:/source:ro -v "${PWD}/backup:/backup" alpine:3.20 `
  tar czf /backup/artio-catalog-media.tar.gz -C /source .
Get-FileHash .\backup\artio-postgres.dump, .\backup\artio-installation-secrets.tar.gz, .\backup\artio-catalog-media.tar.gz -Algorithm SHA256 | Format-Table
docker compose start rest-api event-worker notification-worker
```

The PostgreSQL custom-format dump is transaction-consistent. Stopping the API and both workers also prevents media/database changes while archives are captured. Keep multiple generations, copy the checksum-verified set to encrypted storage outside the host, and test restores in an isolated Compose project before relying on a backup.

## Restore to the same Compose installation

Stop the API and both database workers so no application transaction can race the restore. Restore the exact matching secret volume first, then restore the database dump into the existing PostgreSQL volume:

```powershell
docker compose stop rest-api event-worker notification-worker
docker run --rm -v artio_artio-installation-secrets:/target -v "${PWD}/backup:/backup" alpine:3.20 `
  sh -c 'tar xzf /backup/artio-installation-secrets.tar.gz -C /target && chmod 700 /target && chmod 600 /target/*'
docker run --rm -v artio_artio-catalog-media:/target -v "${PWD}/backup:/backup" alpine:3.20 `
  tar xzf /backup/artio-catalog-media.tar.gz -C /target
docker compose run --rm installation-secrets
$postgres = (docker compose ps -q postgres).Trim()
docker cp .\backup\artio-postgres.dump "${postgres}:/tmp/artio-postgres.dump"
docker compose exec -T postgres dropdb -U artio --if-exists artio
docker compose exec -T postgres createdb -U artio -O artio artio
docker compose exec -T postgres pg_restore -U artio -d artio --no-owner --no-privileges /tmp/artio-postgres.dump
docker compose up -d
```

The same-host procedure drops and recreates the `artio` database so objects created after the backup cannot survive restoration. It assumes the PostgreSQL data volume and database role match the backup set. The explicit `installation-secrets` run refreshes the separate extension-controller secret volume from the restored key material before the controller starts.

For a new host, restore the installation secrets **before PostgreSQL's first initialization**, otherwise PostgreSQL will initialize its role with a newly generated password that does not match the restored secret. From the repository root, create the Compose volumes/containers without starting them, restore the secret and media archives, initialize the controller secret copy, then start only PostgreSQL:

```powershell
docker compose create
docker run --rm -v artio_artio-installation-secrets:/target -v "${PWD}/backup:/backup" alpine:3.20 `
  sh -c 'tar xzf /backup/artio-installation-secrets.tar.gz -C /target && chmod 700 /target && chmod 600 /target/*'
docker run --rm -v artio_artio-catalog-media:/target -v "${PWD}/backup:/backup" alpine:3.20 `
  tar xzf /backup/artio-catalog-media.tar.gz -C /target
docker compose run --rm installation-secrets
docker compose up -d postgres
docker compose ps postgres
$postgres = (docker compose ps -q postgres).Trim()
docker cp .\backup\artio-postgres.dump "${postgres}:/tmp/artio-postgres.dump"
docker compose exec -T postgres pg_restore -U artio -d artio --no-owner --no-privileges /tmp/artio-postgres.dump
docker compose up -d
```

Do not continue until PostgreSQL reports healthy. Verify readiness, setup status, login, product image retrieval, and decryption of an existing plugin configuration before routing traffic.

## Key loss and rotation

If an AES encryption key is lost, PostgreSQL commerce data remains readable but values encrypted with that key cannot be recovered; restore the matching key from the same backup set or configure new provider credentials. The separate `extension-auth-key` must also be restored: changing it changes every plugin runtime credential.

New ciphertexts use `v2.<key-id>.<base64>` AES-256-GCM envelopes. Legacy unversioned ciphertext remains readable while its key is supplied. The active key id is the full 64-character lowercase SHA-256 digest of the UTF-8 key material. During rotation, Artio reads up to eight previous keys from a JSON file in the protected installation secret volume. The file maps each key id to its raw key material; `ARTIO_ENCRYPTION_KEYRING_FILE` contains only the file path. Never put raw key material in `.env`, send it to an Artio API endpoint or commit it to the repository.

To rotate safely:

1. Create a normal encrypted backup set and verify that the matching `extension-auth-key` is retained.
2. Generate a new random active AES key in the installation secrets volume. Keep the prior active key in a JSON file such as `/run/artio-secrets/encryption-keyring.json`, keyed by its derived id, and set `ARTIO_ENCRYPTION_KEYRING_FILE=/run/artio-secrets/encryption-keyring.json` for both API and event-worker. Restart them to load the new active key and prior keyring. Keep `extension-auth-key` unchanged.
3. As SUPERADMIN, call `POST /api/v1/admin/system/security/encryption/rotate`. The endpoint accepts no key material. It locks and rewraps every MFA secret, extension secret, and non-null Kafka dead-letter payload (including already replayed records) in one database transaction and appends an audit event. Null Kafka tombstones remain null; replay status and metadata are preserved. A failure rolls the entire transaction back. Its response reports the active key id and the counts `mfaSecretsRewrapped`, `extensionSecretsRewrapped`, and `deadLetterPayloadsRewrapped`. The last field is an additive API change; no database migration is needed.
4. Verify that MFA authentication and configured plugin invocations still work, then take another database-and-secrets backup. Remove the prior AES key from the keyring and restart API/event-worker only after that verification. Retain the previous backup according to the normal recovery policy.

If the rotation request fails because a ciphertext cannot be decrypted, leave the previous key in the keyring, restore the matching backup if needed, and resolve the key mismatch before retrying. The endpoint is idempotent: values already encrypted by the active key are left unchanged. This flow does not rotate the stable plugin-authentication key.
