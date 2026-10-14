# Artio Brevo Email plugin

This Docker extension implements `notification.channel.v1` over Brevo's transactional email API. Artio Core retains the durable outbox, retry policy and delivery state. The plugin accepts email messages only, sends plain text, reports only the Brevo message reference, and redacts provider response bodies and credentials. Delivery calls have an eight-second timeout and bounded request/response sizes. As with any external email API, a timeout after provider acceptance can cause a retry and duplicate message; the provider adapter does not claim exactly-once email delivery.

Configure a Brevo API key and a sender address verified in Brevo. Set the Core environment to `ARTIO_EMAIL_ENABLED=true`, `ARTIO_NOTIFICATIONS_EMAIL_PROVIDER=extension`, and `ARTIO_NOTIFICATIONS_EXTENSION_PLUGIN_ID=notifications.brevo`. The manifest image remains a template until an image is published and pinned by digest.

## Local checks

```powershell
python -m unittest discover -s artio-plugins/brevo-email -p "test_*.py" -v
docker build -f artio-plugins/brevo-email/Dockerfile -t artio/brevo-email:1.0.0 .
```

The unit tests mock Brevo HTTP and do not claim a provider sandbox or Docker runtime acceptance run.

## References

- [Brevo send transactional email API](https://developers.brevo.com/reference/send-transac-email)
