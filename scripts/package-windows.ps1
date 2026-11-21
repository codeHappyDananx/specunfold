param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenHome,
    [string]$WixHome,
    [ValidateSet('exe', 'msi')][string]$InstallerType = 'exe',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $root '.build/windows'
$tools = Join-Path $root '.build/tools'
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $build, $tools, $dist, "$build/tmp" | Out-Null
$env:TEMP = "$build/tmp"
$env:TMP = $env:TEMP

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Get-VerifiedFile([string]$Url, [string]$Target, [string]$Algorithm, [string]$Hash) {
    if (!(Test-Path -LiteralPath $Target)) { Invoke-WebRequest -Uri $Url -OutFile $Target }
    if ((Get-FileHash -LiteralPath $Target -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Download checksum mismatch: $Target"
    }
}

function Reset-GeneratedDirectory([string]$Directory) {
    $resolved = [IO.Path]::GetFullPath($Directory)
    $allowed = [IO.Path]::GetFullPath($build) + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clear a path outside the packaging build directory: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

if (!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome/bin/jpackage.exe")) {
    throw 'Set -JavaHome to a Windows x64 JDK 21 or newer containing jpackage and jlink.'
}
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
if (!$MavenHome) {
    $MavenHome = Join-Path $tools 'apache-maven-3.9.11'
    $archive = Join-Path $tools 'apache-maven-3.9.11-bin.zip'
    Get-VerifiedFile 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' $archive 'SHA512' '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076'
    if (!(Test-Path -LiteralPath "$MavenHome/bin/mvn.cmd")) { Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force }
}
if (!$WixHome) {
    $WixHome = Join-Path $tools 'wix314'
    $archive = Join-Path $tools 'wix314-binaries.zip'
    Get-VerifiedFile 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' $archive 'SHA256' '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
    if (!(Test-Path -LiteralPath "$WixHome/candle.exe")) { Expand-Archive -LiteralPath $archive -DestinationPath $WixHome -Force }
}
$nodeVersion = '24.9.0'
$nodeArchive = Join-Path $tools "node-v$nodeVersion-win-x64.zip"
Get-VerifiedFile "https://nodejs.org/dist/v$nodeVersion/node-v$nodeVersion-win-x64.zip" $nodeArchive 'SHA256' '6873514c3e6a012917cc6f95ce48a6289253370d025f1b69db290d70feebfa6e'
$nodeHome = Join-Path $tools "node-v$nodeVersion-win-x64"
if (!(Test-Path -LiteralPath "$nodeHome/node.exe")) { Expand-Archive -LiteralPath $nodeArchive -DestinationPath $tools -Force }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome/bin;$MavenHome/bin;$WixHome;$nodeHome;$env:PATH"
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$($env:TEMP) -Dfile.encoding=UTF-8"

Push-Location $root
try {
    if (!$SkipBuild) {
        Invoke-Checked "$MavenHome/bin/mvn.cmd" @('-B', '-s', '.mvn/public-settings.xml', '-gs', '.mvn/public-settings.xml', "-Dmaven.repo.local=$root/.build/m2", 'clean', 'package')
    }
    Invoke-Checked "$nodeHome/node.exe" @('scripts/verify-cli.mjs')
    # The existing audit records hashes of the actual application and libraries being shipped.
    Invoke-Checked 'python' @('scripts/audit-dependencies.py')
    [xml]$pom = Get-Content -LiteralPath "$root/pom.xml" -Raw
    $version = $pom.project.version
    $stage = Join-Path $build 'input'
    $runtime = Join-Path $build 'runtime'
    $images = Join-Path $build 'images'
    # WiX 3 uses legacy Win32 paths. Keep its temporary image prefix short.
    $installerTemp = Join-Path $build 't'
    foreach ($directory in @($stage, $runtime, $images, $installerTemp)) { Reset-GeneratedDirectory $directory }
    New-Item -ItemType Directory -Force -Path $stage, "$stage/mcp-server/target", "$stage/runtime-node", $images | Out-Null
    foreach ($directory in @('bin', 'resources', 'licenses')) {
        Copy-Item -LiteralPath "$root/$directory" -Destination $stage -Recurse
    }
    foreach ($file in @('LICENSE', 'THIRD-PARTY-NOTICES.md')) { Copy-Item -LiteralPath "$root/$file" -Destination $stage }
    Copy-Item -LiteralPath "$PSScriptRoot/windows/USER-GUIDE.md" -Destination "$stage/使用说明.md"
    Copy-Item -LiteralPath "$root/mcp-server/target/specunfold.jar" -Destination "$stage/mcp-server/target"
    Copy-Item -LiteralPath "$root/mcp-server/target/lib" -Destination "$stage/mcp-server/target" -Recurse
    Copy-Item -LiteralPath "$nodeHome/node.exe", "$nodeHome/LICENSE" -Destination "$stage/runtime-node"
    Invoke-Checked "$JavaHome/bin/jlink.exe" @('--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.charsets', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', $runtime)
    if (Test-Path -LiteralPath "$JavaHome/NOTICE") { Copy-Item -LiteralPath "$JavaHome/NOTICE" -Destination $runtime }
    $serverLauncher = Join-Path $build 'server.properties'
    @('arguments=serve', 'win-console=true', 'win-shortcut=false', 'win-menu=false') | Set-Content -LiteralPath $serverLauncher -Encoding ascii
    Invoke-Checked "$JavaHome/bin/jpackage.exe" @('--type', 'app-image', '--name', 'SpecUnfold', '--app-version', $version, '--vendor', 'SpecUnfold', '--input', $stage, '--main-jar', 'mcp-server/target/specunfold.jar', '--main-class', 'dev.specunfold.mcp.DesktopLauncher', '--runtime-image', $runtime, '--dest', $images, '--java-options', '-Dfile.encoding=UTF-8', '--add-launcher', "SpecUnfold-server=$serverLauncher")
    Invoke-Checked 'python' @('scripts/windows/verify-package.py', '--app-dir', "$images/SpecUnfold")
    Invoke-Checked "$JavaHome/bin/jpackage.exe" @('--type', $InstallerType, '--name', 'SpecUnfold', '--app-version', $version, '--vendor', 'SpecUnfold', '--app-image', "$images/SpecUnfold", '--dest', $dist, '--temp', $installerTemp, '--win-dir-chooser', '--win-per-user-install', '--win-menu', '--win-menu-group', 'SpecUnfold', '--win-shortcut-prompt', '--install-dir', 'SpecUnfold', '--win-upgrade-uuid', 'ef219c2a-ec5e-4b47-a3ca-3503042fe182', '--license-file', "$root/LICENSE", '--about-url', 'https://github.com/codeHappyDananx/specunfold')
    $installer = Get-Item -LiteralPath "$dist/SpecUnfold-$version.$InstallerType"
    $manifest = [ordered]@{
        version = $version
        platform = 'windows-x64'
        installer = $installer.Name
        sha256 = (Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = $installer.Length
        java = ((& "$JavaHome/bin/java.exe" -version 2>&1 | Out-String).Trim())
        node = $nodeVersion
        sourceCommit = (& git rev-parse HEAD)
        sourceModified = [bool](& git status --porcelain)
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath "$dist/SpecUnfold-$version-manifest.json" -Encoding utf8
    Write-Host "Installer: $($installer.FullName)"
    Write-Host "Portable application directory: $images/SpecUnfold"
} finally { Pop-Location }
