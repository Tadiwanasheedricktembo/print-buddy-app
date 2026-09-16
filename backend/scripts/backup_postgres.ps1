[CmdletBinding()]
param(
    [string]$Database = $env:PGDATABASE,
    [string]$Host = $env:PGHOST,
    [string]$Port = $env:PGPORT,
    [string]$Username = $env:PGUSER,
    [string]$OutputDir = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) "backups"),
    [string]$BinDir = $env:PG_BIN_DIR,
    [switch]$AllowSystemDb
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProtectedDatabases = @('postgres', 'template0', 'template1')
$AllowedDisposableNames = @('tadiwa_recovery_test', 'tadiwa_restore_test', 'tadiwa_disposable_test')

function Get-DatabaseNameFromUrl([string]$Url) {
    if (-not $Url) { return $null }
    $uri = [System.Uri]::new($Url)
    if ($uri.Scheme -ne 'postgresql' -and $uri.Scheme -ne 'postgres') { return $null }
    return $uri.AbsolutePath.Trim('/')
}

if (-not $Database) {
    throw "Database target is required. Provide -Database or set PGDATABASE."
}

if ($Database -in $ProtectedDatabases -and -not $AllowSystemDb) {
    throw "Refusing to back up protected system database '$Database'. Use -AllowSystemDb only for explicit system DB work."
}

if (-not $Host) { $Host = 'localhost' }
if (-not $Port) { $Port = '5432' }
if (-not $Username) { throw "PostgreSQL username is required. Provide -Username or set PGUSER." }
if (-not $env:PGPASSWORD) { throw "PGPASSWORD is required. Set it in the environment without printing it." }

$environmentName = ($env:ENVIRONMENT, $env:APP_ENV | Where-Object { $_ } | Select-Object -First 1)
if ($environmentName -and $environmentName.ToLower() -eq 'production') {
    throw "Refusing to run backup tooling in production mode."
}

$configuredUrl = $env:DATABASE_URL
if ($configuredUrl) {
    $configuredDb = Get-DatabaseNameFromUrl $configuredUrl
    if ($configuredDb -and $configuredDb.ToLower() -eq $Database.ToLower() -and $Host.ToLower() -in @('localhost', '127.0.0.1', '::1')) {
        throw "Refusing to back up the application's configured database '$Database'."
    }
}

if ($Host.ToLower() -notin @('localhost', '127.0.0.1', '::1')) {
    throw "Disposable recovery target host must be explicitly local. Received '$Host'."
}

if ([int]$Port -ne 5432) {
    throw "Disposable recovery target port must be 5432. Received '$Port'."
}

if ($Database.ToLower() -notin @($AllowedDisposableNames | ForEach-Object { $_.ToLower() })) {
    throw "Refusing to operate on an unknown or ambiguous target database '$Database'. Only explicitly approved disposable targets are allowed."
}

if (-not $BinDir) { $BinDir = 'C:\Program Files\PostgreSQL\17\bin' }
$pgDump = Join-Path $BinDir 'pg_dump.exe'
if (-not (Test-Path $pgDump)) {
    throw "pg_dump.exe not found at '$pgDump'. Set -BinDir or PG_BIN_DIR."
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd_HHmmssZ')
$backupName = "{0}_{1}.dump" -f $Database, $timestamp
$backupPath = Join-Path $OutputDir $backupName

Write-Host "Creating PostgreSQL backup for database '$Database'..."
& $pgDump -h $Host -p $Port -U $Username -d $Database -Fc -f $backupPath

if ($LASTEXITCODE -ne 0) {
    throw "pg_dump failed for database '$Database'."
}

if (-not (Test-Path $backupPath)) {
    throw "Backup file was not created at '$backupPath'."
}

$item = Get-Item $backupPath
if ($item.Length -le 0) {
    throw "Backup file '$backupPath' is empty."
}

Write-Host "Backup created successfully: $backupPath"
Write-Host "Backup size bytes: $($item.Length)"
