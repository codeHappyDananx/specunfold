param([string]$LocalRepository = (Join-Path $PSScriptRoot ".build/m2"))
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    mvn -B -s .mvn/public-settings.xml -gs .mvn/public-settings.xml "-Dmaven.repo.local=$LocalRepository" clean package
    if ($LASTEXITCODE) { throw "Maven build failed" }
} finally { Pop-Location }
