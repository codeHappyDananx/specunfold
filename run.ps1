$ErrorActionPreference = "Stop"
& node (Join-Path $PSScriptRoot "bin/specunfold.js") serve @args
exit $LASTEXITCODE
