#!/bin/sh
set -eu
secret_dir=/run/artio-secrets

for name in database-password jwt-secret encryption-key setup-token; do
    file="$secret_dir/$name"
    if [ ! -s "$file" ]; then
        echo "Required installation secret is missing: $name" >&2
        exit 1
    fi
done

export ARTIO_DATABASE_PASSWORD="$(cat "$secret_dir/database-password")"
export ARTIO_JWT_SECRET="$(cat "$secret_dir/jwt-secret")"
export ARTIO_ENCRYPTION_KEY="$(cat "$secret_dir/encryption-key")"
export ARTIO_SETUP_TOKEN="$(cat "$secret_dir/setup-token")"
exec java -jar /app/app.jar
