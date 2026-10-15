UPDATE pricing_tax_rule
SET valid_until = TIMESTAMPTZ '2028-01-01 00:00:00+00'
WHERE id = '00000000-0000-0000-0000-000000000038'
  AND jurisdiction = 'CH'
  AND tax_category = 'ACCOMMODATION'
  AND valid_from = TIMESTAMPTZ '2024-01-01 00:00:00+00'
  AND valid_until IS NULL;
