# Windows version of fetch-catalog.sh: pinned commit + SHA-256 check.
$ErrorActionPreference = "Stop"
$Commit = "5006f18c8047643eaa70e35dd0d20a58bcc41856"
$Sha256 = "64ca6b4161de51ab6af098a7d101d326f14c95e1abf721396cd8a049e8115eeb"
$Url = "https://raw.githubusercontent.com/usnistgov/oscal-content/$Commit/nist.gov/SP800-53/rev5/json/NIST_SP-800-53_rev5_catalog.json"
$Out = Join-Path $PSScriptRoot "..\data\NIST_SP-800-53_rev5_catalog.json"

New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
if ((Test-Path $Out) -and (Get-FileHash $Out -Algorithm SHA256).Hash -eq $Sha256) { "catalog already present and verified"; exit 0 }
Invoke-WebRequest $Url -OutFile $Out -UseBasicParsing
$got = (Get-FileHash $Out -Algorithm SHA256).Hash
if ($got -ne $Sha256) { Remove-Item $Out; throw "SHA-256 mismatch: got $got" }
"catalog downloaded and verified"
