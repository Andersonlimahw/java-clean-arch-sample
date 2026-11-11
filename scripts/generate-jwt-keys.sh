#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-./jwt-keys}"
mkdir -p "$OUTPUT_DIR"

echo "==> Generating 2048-bit RSA private key..."
openssl genpkey -algorithm RSA -out "$OUTPUT_DIR/private_raw.pem" -pkeyopt rsa_keygen_bits:2048

echo "==> Converting private key to PKCS#8..."
openssl pkcs8 -topk8 -inform PEM -outform PEM -in "$OUTPUT_DIR/private_raw.pem" -out "$OUTPUT_DIR/private.pem" -nocrypt
rm "$OUTPUT_DIR/private_raw.pem"

echo "==> Extracting public key (X.509)..."
openssl rsa -pubout -in "$OUTPUT_DIR/private.pem" -out "$OUTPUT_DIR/public.pem"

chmod 600 "$OUTPUT_DIR/private.pem"
chmod 644 "$OUTPUT_DIR/public.pem"

echo "==> Keys generated successfully in $OUTPUT_DIR:"
ls -la "$OUTPUT_DIR"
