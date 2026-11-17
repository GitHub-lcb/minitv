<#
    Bootstrap the local MySQL database for the Java service.

    Usage (PowerShell, from the repository root):
        .\scripts\init_mysql.ps1
        .\scripts\init_mysql.ps1 -MysqlExe "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

    It asks for the MySQL administrator password (never stored), creates the
    `minitv` schema plus the `minitv` / `minitv_local` local account, and enables
    trigger creation for Flyway. Safe to re-run.
#>
param(
    [string]$MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$RootUser = "root"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sqlFile = Join-Path $PSScriptRoot "init_mysql.sql"

if (-not (Test-Path $MysqlExe)) {
    throw "mysql.exe not found at '$MysqlExe'. Pass -MysqlExe with your installation path."
}
if (-not (Test-Path $sqlFile)) {
    throw "missing $sqlFile"
}

Write-Host "MySQL bootstrap: $HostName`:$Port (user $RootUser)"
$secure = Read-Host "MySQL $RootUser password" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

# MYSQL_PWD keeps the secret out of the process command line. It is still
# process-scoped and cleared immediately afterwards.
$env:MYSQL_PWD = $plain
try {
    Get-Content $sqlFile -Raw | & $MysqlExe "--host=$HostName" "--port=$Port" "--user=$RootUser" --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) {
        throw "mysql exited with code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    $plain = $null
}

Write-Host ""
Write-Host "Done. Verify with:"
Write-Host "  & '$MysqlExe' --host=$HostName --port=$Port --user=minitv --password=minitv_local -e `"SHOW DATABASES LIKE 'minitv'`""
Write-Host ""
Write-Host "Next: make sure .env has"
Write-Host "  DATABASE_URL=mysql://minitv:minitv_local@$HostName`:$Port/minitv"
Write-Host "then start the API:  cd server; mvn spring-boot:run"
