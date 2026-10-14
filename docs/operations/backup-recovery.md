# Backup and recovery for a single Artio installation

Artio stores commerce records in PostgreSQL and installation credentials in the `artio-installation-secrets` named volume. The secrets volume contains the database password, JWT signing secret, AES-GCM encryption key and first-run setup token. Back up both; a database dump without the matching AES key cannot decrypt saved plugin secrets.

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

## Key loss and rotation status

If the AES key is lost, PostgreSQL commerce data remains readable but encrypted plugin credentials cannot be recovered; configure new provider credentials. Restore the matching key from the same backup set to recover them.

The current AES-GCM ciphertext format uses one active installation key and does not yet provide versioned envelopes or an online key rotation workflow. Do not replace `encryption-key` in a live installation: existing plugin secrets would become unreadable. Key versioning, rotation, and restore acceptance remain open Phase 2 work tracked in [ROADMAP.md](../ROADMAP.md).
