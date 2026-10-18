[CmdletBinding()]
param([string]$RuntimeRoot = (Join-Path $PSScriptRoot '../data/tools/libreoffice'))
$ErrorActionPreference = 'Stop'
$version = '26.8.1'
$expectedHash = 'c6298b10cfa1748bcbedad94c62ce771e240e5021bfd6d7a967cd571b04b5312'
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
$installer = Join-Path $RuntimeRoot "LibreOffice_${version}_Win_x86-64.msi"
$url = "https://download.documentfoundation.org/libreoffice/stable/$version/win/x86_64/LibreOffice_${version}_Win_x86-64.msi"
if (-not (Test-Path -LiteralPath $installer)) {
    Invoke-WebRequest -Uri $url -OutFile $installer
}
if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'LibreOffice installer SHA-256 mismatch; no extraction performed.'
}
$signature = Get-AuthenticodeSignature -LiteralPath $installer
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'The Document Foundation') {
    throw 'LibreOffice installer signature is not valid for The Document Foundation.'
}
$runtime = Join-Path $RuntimeRoot 'runtime'
$executable = Join-Path $runtime 'program/soffice.com'
if (-not (Test-Path -LiteralPath $executable)) {
    if (Test-Path -LiteralPath $runtime) {
        throw "Incomplete runtime already exists at $runtime; select a new RuntimeRoot."
    }
    $log = Join-Path $RuntimeRoot 'extract.log'
    # Administrative image only: no application registration or file association changes.
    $arguments = '/a "{0}" /qn /norestart TARGETDIR="{1}" /L*v "{2}"' -f $installer, $runtime, $log
    $process = Start-Process msiexec.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "LibreOffice extraction failed; see $log" }
}
$python = Join-Path $runtime 'program/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Bundled UNO Python is missing.' }
$actualVersion = & $executable --headless --version
if ($LASTEXITCODE -ne 0 -or $actualVersion -notmatch 'LibreOffice 26\.8\.1') { throw 'Runtime version check failed.' }
& $python -c 'import uno; print("UNO Python available")'
if ($LASTEXITCODE -ne 0) { throw 'UNO Python import failed.' }
[ordered]@{ source=$url; sha256=$expectedHash; version="$actualVersion"; executable=$executable; python=$python } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RuntimeRoot 'runtime-manifest.json') -Encoding utf8
Write-Output "PREVIEW_OFFICE_BACKEND=libreoffice"
Write-Output "PREVIEW_OFFICE_EXECUTABLE=$executable"
Write-Output "PREVIEW_OFFICE_PYTHON=$python"
