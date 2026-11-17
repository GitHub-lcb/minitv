<#
    Start the whole MiniTV dev stack in separate PowerShell windows.

    Usage (from the repository root):
        .\scripts\start-dev.ps1                 # backend + admin + web + h5
        .\scripts\start-dev.ps1 -NoFrontends    # backend only
        .\scripts\start-dev.ps1 -NoBackend      # frontends only

    Requirements: JDK 21 + Maven (backend), Node.js 18+ (frontends), and MySQL
    + Redis already running with the .env values pointing at them.
#>
param(
    [switch]$NoBackend,
    [switch]$NoFrontends
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Start-DevWindow {
    param([string]$Title, [string]$WorkingDir, [string]$Command)
    Start-Process -FilePath "powershell" -ArgumentList @(
        "-NoExit",
        "-Command",
        "`$host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$WorkingDir'; $Command"
    ) | Out-Null
    Write-Host "  started $Title  -> $WorkingDir"
}

function Test-Port {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect("127.0.0.1", $Port)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

Write-Host "MiniTV dev stack"
if (-not $NoBackend) {
    Start-DevWindow -Title "minitv-api" -WorkingDir (Join-Path $root "server") -Command "mvn spring-boot:run"
}
if (-not $NoFrontends) {
    Start-DevWindow -Title "minitv-admin" -WorkingDir (Join-Path $root "admin") -Command "npm run dev"
    Start-DevWindow -Title "minitv-web" -WorkingDir (Join-Path $root "web") -Command "npm run dev"
    Start-DevWindow -Title "minitv-h5" -WorkingDir (Join-Path $root "h5") -Command "npm run dev"
}

Write-Host ""
Write-Host "Waiting for services (first Maven/Vite start can take a while)..."
Start-Sleep -Seconds 10

$checks = @(
    @{ Name = "API"; Port = 18110; Url = "http://127.0.0.1:18110/health" },
    @{ Name = "admin"; Port = 5173; Url = "http://localhost:5173/" },
    @{ Name = "web"; Port = 5174; Url = "http://localhost:5174/" },
    @{ Name = "h5"; Port = 5175; Url = "http://localhost:5175/" }
)
foreach ($check in $checks) {
    $ok = Test-Port -Port $check.Port
    Write-Host ("  {0,-6} {1,-5} {2}" -f $check.Name, $(if ($ok) { "UP" } else { "WAIT" }), $check.Url)
}
Write-Host ""
Write-Host "Admin: http://localhost:5173  (first run: see README step 4 for the admin account)"
Write-Host "If a window closed immediately, run its command manually to see the error."
