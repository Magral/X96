param(
    [Parameter(Mandatory = $true)][string]$BackupFolder,
    [string]$Repository = 'Magral/X96'
)
$ErrorActionPreference = 'Stop'
# Run with the PRIVATE signing backup extracted outside the public repository.
# Secrets are passed on stdin, never in command arguments or printed to the console.
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'Install GitHub CLI: https://cli.github.com/ and run gh auth login.'
}
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Run gh auth login, then run this script again.' }
$configPath = Join-Path $BackupFolder 'signing.json'
$keyPath = Join-Path $BackupFolder 'x96-local.p12'
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if ($config.applicationId -ne 'ru.x96.local') { throw 'The backup belongs to another application.' }
$bundle = @{
    alias = $config.alias
    password = $config.password
    keystore_base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $keyPath).Path))
} | ConvertTo-Json -Compress
try {
    $bundle | & gh secret set X96_SIGNING_BUNDLE --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw 'GitHub did not save the secret.' }
    Write-Host 'Signing configured. Starting the main-branch Android APK workflow...'
    & gh workflow run android.yml --repo $Repository --ref main
    if ($LASTEXITCODE -ne 0) { throw 'Secret saved; start Android APK manually in the Actions tab.' }
    Write-Host "Done. Follow the build at https://github.com/$Repository/actions"
} finally {
    $bundle = $null
    $config = $null
}
