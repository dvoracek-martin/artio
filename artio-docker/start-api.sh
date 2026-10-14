#!/bin/sh
set -eu
runtime_role=${ARTIO_RUNTIME_ROLE:-api}
if [ "$runtime_role" = "extension-controller" ]; then
    secret_dir=/run/artio-controller-secrets
    required_secrets="extension-auth-key extension-controller-token"
else
    secret_dir=/run/artio-secrets
    required_secrets="database-password jwt-secret encryption-key extension-auth-key setup-token extension-controller-token"
fi

for name in $required_secrets; do
    file="$secret_dir/$name"
    if [ ! -s "$file" ]; then
        echo "Required installation secret is missing: $name" >&2
        exit 1
    fi
done

export ARTIO_EXTENSION_AUTH_KEY="$(cat "$secret_dir/extension-auth-key")"
export ARTIO_EXTENSION_CONTROLLER_TOKEN="$(cat "$secret_dir/extension-controller-token")"
if [ "$runtime_role" = "extension-controller" ]; then
    exec java -jar /app/extension-controller.jar
fi
export ARTIO_ENCRYPTION_KEY="$(cat "$secret_dir/encryption-key")"
export ARTIO_DATABASE_PASSWORD="$(cat "$secret_dir/database-password")"
export ARTIO_JWT_SECRET="$(cat "$secret_dir/jwt-secret")"
export ARTIO_SETUP_TOKEN="$(cat "$secret_dir/setup-token")"
exec java -jar /app/app.jar
