# Flyway clean-install baseline

`artio-rest-api/src/main/resources/db/migration/V1__initial_schema.sql` is the sole schema migration for new Artio installations. It consolidates the pre-release V1–V27 SQL in order. The project owner confirmed that no external customer data needs to be upgraded, so compatibility with databases that ran the former migration sequence is not supported.

## Validate without touching an existing database

Use a new Compose project name to give the validation run its own PostgreSQL and secrets volumes:

```powershell
docker compose -p artio-baseline-v1-check up -d --build --wait --wait-timeout 300
docker compose -p artio-baseline-v1-check logs rest-api
docker compose -p artio-baseline-v1-check down
```

The final `down` stops and removes that temporary project's containers and network but retains its named volumes. Existing Artio project volumes are not selected or modified. Choose a different project name for an independent fresh-database run.

## Existing local development database

An existing local database may still have Flyway history for the old migration set; it is not an upgrade target for this baseline and may fail Flyway validation. Do not delete it implicitly or use `docker compose down -v` as a routine startup step. Preserve it or take a database backup first. To test the new baseline, use the isolated Compose project above. Any intentional disposal of a local database must be performed separately after verifying its exact volume name and confirming it contains no data that needs to be retained.
