#!/bin/sh
set -eu

: "${MINIO_ROOT_USER_FILE:=/run/secrets/minio-root-user}"
: "${MINIO_ROOT_PASSWORD_FILE:=/run/secrets/minio-root-password}"
export MINIO_ROOT_USER_FILE MINIO_ROOT_PASSWORD_FILE

if [ ! -s "$MINIO_ROOT_USER_FILE" ] || [ ! -s "$MINIO_ROOT_PASSWORD_FILE" ]; then
  printf '%s\n' 'MinIO root credentials must be supplied through protected secret files.' >&2
  exit 78
fi

if [ "$(cat "$MINIO_ROOT_USER_FILE")" = 'minioadmin' ] && [ "$(cat "$MINIO_ROOT_PASSWORD_FILE")" = 'minioadmin' ]; then
  printf '%s\n' 'The upstream demo credential pair is forbidden.' >&2
  exit 78
fi

exec /usr/bin/minio "$@"
