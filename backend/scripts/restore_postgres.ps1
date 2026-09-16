[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [Parameter(Mandatory = $true)]
    [string]$TargetDatabase,
    [string]$Host = $env:PGHOST,
    [string]$Port = $env:PGPORT,
    [string]$Username = $env:PGUSER,
    [string]$BinDir = $env:PG_BIN_DIR,
    [switch]$Recreate,
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

if (-not (Test-Path $BackupFile)) {
    throw "Backup file not found: $BackupFile"
}

if (-not $Host) { $Host = 'localhost' }
if (-not $Port) { $Port = '5432' }
if (-not $Username) { throw "PostgreSQL username is required. Provide -Username or set PGUSER." }
if (-not $env:PGPASSWORD) { throw "PGPASSWORD is required. Set it in the environment without printing it." }

if ($TargetDatabase -in $ProtectedDatabases -and -not $AllowSystemDb) {
    throw "Refusing to restore into protected system database '$TargetDatabase'."
}

$environmentName = ($env:ENVIRONMENT, $env:APP_ENV | Where-Object { $_ } | Select-Object -First 1)
if ($environmentName -and $environmentName.ToLower() -eq 'production') {
    throw "Refusing to run restore tooling in production mode."
}

$configuredUrl = $env:DATABASE_URL
if ($configuredUrl) {
    $configuredDb = Get-DatabaseNameFromUrl $configuredUrl
    if ($configuredDb -and $configuredDb.ToLower() -eq $TargetDatabase.ToLower() -and $Host.ToLower() -in @('localhost', '127.0.0.1', '::1')) {
        throw "Refusing to restore into the application's configured database '$TargetDatabase'."
    }
}

if ($Host.ToLower() -notin @('localhost', '127.0.0.1', '::1')) {
    throw "Disposable recovery target host must be explicitly local. Received '$Host'."
}

if ([int]$Port -ne 5432) {
    throw "Disposable recovery target port must be 5432. Received '$Port'."
}

if ($TargetDatabase.ToLower() -notin @($AllowedDisposableNames | ForEach-Object { $_.ToLower() })) {
    throw "Refusing to operate on an unknown or ambiguous target database '$TargetDatabase'. Only explicitly approved disposable targets are allowed."
}

if (-not $BinDir) { $BinDir = 'C:\Program Files\PostgreSQL\17\bin' }
$psql = Join-Path $BinDir 'psql.exe'
$createdb = Join-Path $BinDir 'createdb.exe'
$dropdb = Join-Path $BinDir 'dropdb.exe'
$pgRestore = Join-Path $BinDir 'pg_restore.exe'

foreach ($tool in @($psql, $createdb, $dropdb, $pgRestore)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}

$existing = & $psql -h $Host -p $Port -U $Username -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$TargetDatabase';"
if ($existing.Trim() -eq '1') {
    if (-not $Recreate) {
        throw "Target database '$TargetDatabase' already exists. Use -Recreate to destroy and recreate it explicitly."
    }
    Write-Host "Dropping existing database '$TargetDatabase'..."
    & $dropdb -h $Host -p $Port -U $Username --if-exists $TargetDatabase
    if ($LASTEXITCODE -ne 0) { throw "Failed to drop database '$TargetDatabase'." }
}

Write-Host "Creating target database '$TargetDatabase'..."
& $createdb -h $Host -p $Port -U $Username $TargetDatabase
if ($LASTEXITCODE -ne 0) { throw "Failed to create database '$TargetDatabase'." }

Write-Host "Restoring backup into '$TargetDatabase'..."
& $pgRestore -h $Host -p $Port -U $Username -d $TargetDatabase --clean --if-exists $BackupFile
if ($LASTEXITCODE -ne 0) { throw "pg_restore failed for '$BackupFile' into '$TargetDatabase'." }

Write-Host "Restore completed successfully into '$TargetDatabase'."
