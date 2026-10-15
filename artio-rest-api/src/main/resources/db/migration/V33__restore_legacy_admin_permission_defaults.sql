-- Existing administrators without an explicit permission list keep the access they had before permission enforcement.
UPDATE users
SET permissions = '*'
WHERE role = 'ADMIN'
  AND btrim(permissions) = '';
