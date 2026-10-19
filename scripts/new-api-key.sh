#!/usr/bin/env bash
# Prints a fresh random API key and its API_KEY_HASHES entry for .env.
# Usage: scripts/new-api-key.sh <client-id> <operator|readonly>
set -euo pipefail

client="${1:?usage: scripts/new-api-key.sh <client-id> <operator|readonly>}"
role="${2:?usage: scripts/new-api-key.sh <client-id> <operator|readonly>}"
case "$role" in operator | readonly) ;; *) echo "role must be operator or readonly" >&2; exit 1 ;; esac

key="tams_${client}_$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
hash="$(printf '%s' "$key" | sha256sum | cut -d' ' -f1)"

echo "key:   ${key}"
echo "entry: ${client}:${role}:${hash}   (comma-separate entries in API_KEY_HASHES)"
