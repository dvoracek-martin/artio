# Scheduled product publication v1

Inactive, non-archived product drafts can be scheduled to become active at an absolute UTC timestamp. Product detail and Backoffice page responses expose `scheduledPublishAt` as an ISO-8601 instant.

## API

- `PUT /api/v1/products/{id}/publication-schedule` with `{ "publishAt": "2030-01-01T12:00:00Z" }` schedules a future time.
- `DELETE /api/v1/products/{id}/publication-schedule` cancels the pending publication and leaves the product inactive.
- Both endpoints require an administrator. The API rejects timestamps that are not in the future, active products, archived products, and cancellation when no schedule exists.
- Manual activation cancels a pending schedule. Archiving also clears it.

## Processing and consistency

The API scheduler polls every 15 seconds by default (`artio.catalog.publication-poll-interval-ms`) and claims at most 100 due rows per batch. PostgreSQL `FOR UPDATE SKIP LOCKED` allows multiple API instances to run without claiming the same product. Persisted activation and clearing the schedule happen in one database transaction; the product is also sent to the configured search index. Domain events distinguish schedule, cancellation and publication transitions.

## Verification

Domain tests cover future-time validation, cancellation, due-time activation and manual activation clearing a schedule. The PostgreSQL API integration case covers scheduling, staff authorization and due publication, but Testcontainers reported it `SKIPPED` because Docker Engine was unavailable. Command results are recorded in `docs/ROADMAP.md`.
