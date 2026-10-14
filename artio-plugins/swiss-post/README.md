# Swiss Post shipping plugin source

This Docker plugin implements Artio's `shipping.provider.v1` contract for domestic Switzerland shipments. It supports merchant-configured Economy/Priority CHF rates and label creation through Swiss Post's Digital Commerce Barcode API. Prices are entered from the merchant's own Swiss Post contract; the plugin does not invent carrier rates.

## Configuration

Register the client with Swiss Post Digital Commerce and request the `DCAPI_BARCODE_READ` scope. Configure the issued client ID/secret, franking licence and sender address in the Artio extension settings. Artio stores the secret encrypted. The operator supplies the ECO/PRI checkout prices in CHF. By default `printPreview` is enabled and Swiss Post marks created labels as specimens; disable it only after the merchant's credentials and workflow are accepted for live label generation.

Swiss Post's client credentials authorize access to a registered client; actual API products and franking licence are subject to the merchant's Swiss Post contract. The published API documentation describes OAuth client credentials and `POST /barcode/v1/generateAddressLabel`. It also says the client must be registered by Swiss Post and a franking licence is required. No provider credentials or test environment are included in this repository.

## Contract support

- `capabilities`: obtains an OAuth token and reports CH label creation, merchant-rate quotes and no tracking support.
- `methods`: returns PostPac Economy (`ECO`) and Priority (`PRI`) for CH/CHF.
- `quote`: returns the merchant's configured `shippingPricesChf` rate.
- `createShipment`: requests an A6/A7 PDF label, returns Swiss Post's identCode and a PDF data URL. The request uses provider `SendingID`/`ItemID` and a persistent SQLite idempotency ledger. If the provider outcome is ambiguous, the same request is blocked from creating another label and requires operator reconciliation.
- `track`: explicitly reports unsupported until the merchant has the separate Swiss Post tracking product/entitlement and an endpoint contract is configured.

This source package is not yet an installable release: the Artio image digest remains an explicit placeholder until a release pipeline builds and publishes the image. Sandbox/live API behavior and actual merchant acceptance must be verified with Swiss Post-issued credentials before production use. The plugin does not claim cancellation, live carrier pricing, delivery dates, or tracking updates.

## Local checks

```powershell
python -m unittest discover -s artio-plugins/swiss-post -p 'test_*.py' -v
```

The tests mock provider responses. They verify quote mapping, label idempotency and uncertainty handling; they do not verify Swiss Post's live service.
