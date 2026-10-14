# Privileged account MFA v1

`ADMIN` and `SUPERADMIN` accounts must enroll and verify TOTP before accessing any protected Artio API. Customer accounts are not required to enroll.

## First sign-in

`POST /api/v1/auth/login` accepts `email`, `password`, and an optional `mfaCode`. If a privileged account has not verified MFA, the successful login response includes `mfaEnrollmentRequired: true` and an access token with setup-only scope. This token can call only the TOTP enrollment and verification routes. Other protected APIs return `403 Forbidden` until MFA is verified and the user signs in again.

1. Call `POST /api/v1/auth/mfa/totp/enrollment` with the setup-only bearer token. The response contains a TOTP secret and `otpauth://` URI. An unverified abandoned enrollment is replaced when enrollment is restarted.
2. Add the Artio account to an authenticator app and submit its six-digit current code to `POST /api/v1/auth/mfa/totp/verify`.
3. Save the returned one-time backup codes. They are stored as password hashes and cannot be retrieved again.
4. Sign in again with the password and a current TOTP or unused backup code. The response then has `mfaEnrollmentRequired: false` and grants the normal role token.

The Backoffice guides this flow and displays the enrollment URI/manual secret and one-time backup codes only in the active page. It does not store the authenticator secret or backup codes in browser storage.

## Lifecycle and recovery

Privileged accounts cannot disable MFA through the account endpoint. Staff role assignment does not bypass enrollment: the next login is restricted to setup scope until verified. A verified account must provide TOTP or a backup code at login. Backup codes are consumed transactionally and one time only. Lost authenticator and backup-code recovery requires a controlled system-owner recovery procedure; there is no API that returns existing secrets.

The optional OIDC login does not issue privileged sessions. Privileged users use Artio password and TOTP sign-in so the server can enforce its own second factor.
