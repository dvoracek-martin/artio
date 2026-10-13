# Regional tax policy

## Switzerland baseline

The Federal Tax Administration lists the Swiss VAT rates effective from 1 January 2024 as 8.1% normal, 2.6% reduced, and 3.8% accommodation. The rates and their legal scope are defined by the Federal VAT Act and official guidance:

- Rates and examples: <https://www.estv.admin.ch/en/vat-rates-switzerland>
- Rate history/effective date: <https://www.estv.admin.ch/en/development-swiss-vat-rates>
- Federal VAT Act, Article 25: <https://www.estv.admin.ch/dam/estv/en/dokumente/mwst/publikationen/mwst-publ-vat-act-en.pdf.download.pdf/mwst-publ-vat-act-en.pdf>

Migration V17 seeds these three effective-dated rates for jurisdiction `CH`. Checkout now resolves Switzerland to market `CH`, jurisdiction `CH`, and currency `CHF`. Sellables use `STANDARD` unless an administrator explicitly assigns `REDUCED` or `ACCOMMODATION` through `PUT /api/v1/tax-rules/sellables/{sellableId}/category`. The assigned category and effective rate are copied into the order's tax snapshot.

The category assignment is a merchant policy decision. It does not classify catalog products automatically and must not infer eligibility from names or descriptions. Reduced and accommodation rates apply only to legally defined goods or services; operators remain responsible for classification and invoice rules. Every installation should review current Federal Tax Administration guidance before using these rates for live orders. Each effective price can be explicitly marked tax-inclusive or tax-exclusive. In the inclusive mode, discounts apply to the customer-facing gross price first, then Core extracts the tax-exclusive taxable amount and tax while preserving the discounted gross total. The inclusion mode is captured in the quote and immutable order tax snapshot.

Other jurisdictions remain configured by administrators. VAT registration thresholds, place-of-supply rules, exemptions, reverse charge, import VAT, international rounding/invoicing rules, and automatic product classification remain unimplemented.
