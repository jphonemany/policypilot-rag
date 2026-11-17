#!/usr/bin/env bash
# Download the NIST SP 800-53 Rev 5 OSCAL catalog, pinned to one commit and checked by SHA-256,
# so every run (yours, CI, a reviewer's) ingests exactly the same data.
set -euo pipefail

COMMIT=5006f18c8047643eaa70e35dd0d20a58bcc41856
SHA256=64ca6b4161de51ab6af098a7d101d326f14c95e1abf721396cd8a049e8115eeb
URL="https://raw.githubusercontent.com/usnistgov/oscal-content/${COMMIT}/nist.gov/SP800-53/rev5/json/NIST_SP-800-53_rev5_catalog.json"
OUT="$(dirname "$0")/../data/NIST_SP-800-53_rev5_catalog.json"

mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ] && echo "${SHA256}  ${OUT}" | sha256sum -c --status; then
  echo "catalog already present and verified"; exit 0
fi
curl -fsSL "$URL" -o "$OUT"
echo "${SHA256}  ${OUT}" | sha256sum -c -
