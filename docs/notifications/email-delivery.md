# Order email delivery

Checkout writes the in-app order notification and an email delivery row in the same PostgreSQL transaction as the order. A rollback therefore cannot leave a notification for an order that was not created. The delivery row contains the user and order identifiers plus message content; the recipient address is loaded from the active user record when a worker claims the row.

Sending is opt-in. Set `ARTIO_EMAIL_ENABLED=true` after configuring the `ARTIO_SMTP_*` variables and sender address. The poll interval is controlled by `ARTIO_EMAIL_POLL_INTERVAL_MS` and defaults to 30 seconds. With email disabled or no mail sender available, pending rows remain queued without consuming attempts.

The worker claims up to 25 rows with `FOR UPDATE SKIP LOCKED`, uses a five-minute claim lease, and retries failures with exponential backoff from 30 seconds up to one hour. After 12 failed attempts it sets state `DEAD`. The outbox stores only the error class, not exception text. Successful and terminal records have their subject and body redacted. Account erasure marks pending/in-flight records terminal and clears message content.

Delivery is at least once: if SMTP accepts a message and the worker stops before recording success, the lease will expire and the message may be sent again. SMTP does not offer a general idempotency key. Monitor `notification_delivery_outbox` counts by `PENDING`, `PROCESSING`, and `DEAD`; operational replay tooling and retention policy remain future work.
