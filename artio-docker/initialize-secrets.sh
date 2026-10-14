#!/bin/sh
set -eu
umask 077

secret_dir=/run/artio-secrets
mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

random_hex() {
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

write_secret_once() {
    name=$1
    supplied=$2
    target="$secret_dir/$name"
    if [ -s "$target" ]; then
        return
    fi
    if [ -n "$supplied" ]; then
        normalized=$(printf '%s' "$supplied" | tr '[:upper:]' '[:lower:]')
        case "$normalized" in
            *replace-with*|*change-this*|*local-development*|*your-secret*)
                echo "Refusing placeholder value for $name" >&2
                exit 1
                ;;
        esac
        value=$supplied
    else
        value=$(random_hex)
    fi
    if [ "${#value}" -lt 32 ]; then
        echo "Configured value for $name must contain at least 32 characters" >&2
        exit 1
    fi
    printf '%s\n' "$value" > "$target"
    chmod 600 "$target"
}

write_secret_once database-password "${ARTIO_DATABASE_PASSWORD:-}"
write_secret_once jwt-secret "${ARTIO_JWT_SECRET:-}"
write_secret_once encryption-key "${ARTIO_ENCRYPTION_KEY:-}"
write_secret_once setup-token "${ARTIO_SETUP_TOKEN:-}"
