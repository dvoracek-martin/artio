# Guided first-run setup

The Backoffice first-run screen is a five-step wizard rather than a single long form:

1. **Welcome:** verify the installation setup token and resume the saved non-secret draft.
2. **Your store:** set the store name and optional HTTPS public URL.
3. **Region & languages:** searchable country, currency, locale and time-zone choices; searchable multiple-language selection with checkmarks and selected chips. Defaults are Switzerland, CHF, de-CH, Europe/Zurich and DE/FR/IT/EN content languages.
4. **Administrator:** owner identity, password, password confirmation and a show-password control.
5. **Review & launch:** summary with Edit actions; explicit final launch, followed by a success screen and sign-in action.

Each step validates before continuing. Back preserves current browser fields. The token-protected resume happens before editing, so an old server draft cannot overwrite new edits during launch. The existing progress API requires a complete identity and region payload: non-secret progress is therefore saved after the administrator step and again before completion, rather than claiming that incomplete early steps are saved server-side. Refreshing requires token verification again; only a previously saved non-secret draft can be resumed. Passwords and confirmation stay in component memory and never enter progress requests or browser storage.

`/api/v1/setup/status`, `/resume`, `/progress` and `/complete` remain unchanged. The first password is sent only to `/complete`, and token/password fields are cleared after success. The new frontend does not alter authentication, token rate limits, region provisioning or production MFA. Developer mode preserves the documented prefills; sign-in prefills are kept only when the user actually completed setup with the unchanged demo identity and password.

Modern selection behavior and reusable components are described in [Backoffice interaction patterns](../backoffice-ux.md). Setup styles live in the Backoffice layout stylesheet, using Artio's existing brand tokens.
