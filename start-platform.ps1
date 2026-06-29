param(
    [switch]$SkipInstall,
    [switch]$SkipFrontendInstall,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendModule = "company-platform"
$FrontendDir = Join-Path $RepoRoot "frontend\live-console"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Start-PlatformProcess {
    param(
        [string]$Title,
        [string]$WorkingDirectory,
        [string]$Command
    )

    $escapedTitle = $Title.Replace("'", "''")
    $escapedWorkdir = $WorkingDirectory.Replace("'", "''")
    $escapedCommand = $Command.Replace("'", "''")
    $psCommand = "& { `$Host.UI.RawUI.WindowTitle = '$escapedTitle'; Set-Location '$escapedWorkdir'; $escapedCommand }"

    Start-Process `
        -FilePath "powershell" `
        -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $psCommand) `
        -WorkingDirectory $WorkingDirectory
}

Require-Command "java"
Require-Command "mvn"

if (-not $BackendOnly) {
    Require-Command "node"
    Require-Command "npm"
    if (-not (Test-Path $FrontendDir)) {
        throw "Frontend directory not found: $FrontendDir"
    }
}

if (-not $FrontendOnly -and -not $SkipInstall) {
    Push-Location $RepoRoot
    try {
        Write-Host "[platform] Installing backend reactor dependencies..."
        mvn -pl $BackendModule -am -DskipTests install
    } finally {
        Pop-Location
    }
}

if (-not $BackendOnly -and -not $SkipFrontendInstall) {
    $NodeModules = Join-Path $FrontendDir "node_modules"
    if (-not (Test-Path $NodeModules)) {
        Push-Location $FrontendDir
        try {
            Write-Host "[platform] Installing frontend dependencies..."
            npm install
        } finally {
            Pop-Location
        }
    }
}

if (-not $FrontendOnly) {
    $backendCommand = "mvn -pl $BackendModule -DskipTests spring-boot:run -Dspring-boot.run.arguments=--server.port=$BackendPort"
    Start-PlatformProcess `
        -Title "Company Platform Backend :$BackendPort" `
        -WorkingDirectory $RepoRoot `
        -Command $backendCommand
}

if (-not $BackendOnly) {
    $frontendCommand = "npm run dev -- --host 0.0.0.0 --port $FrontendPort"
    Start-PlatformProcess `
        -Title "Company Platform Frontend :$FrontendPort" `
        -WorkingDirectory $FrontendDir `
        -Command $frontendCommand
}

Write-Host ""
Write-Host "[platform] Startup commands dispatched."
if (-not $FrontendOnly) {
    Write-Host "[platform] Backend:  http://127.0.0.1:$BackendPort"
}
if (-not $BackendOnly) {
    Write-Host "[platform] Frontend: http://127.0.0.1:$FrontendPort/platform/live?domain=platform&org_id=platform&user_id=platform_admin"
}
