# Run LiteCam MRZ Scanner
# PowerShell script to run the Maven-built application

$ErrorActionPreference = "Stop"

$JarPath = "target\litecam-document-scanner-1.0.0.jar"

Write-Host "Starting LiteCam Document Scanner..." -ForegroundColor Green

# Check if JAR exists
if (-not (Test-Path $JarPath)) {
    Write-Host "Error: JAR file not found at $JarPath" -ForegroundColor Red
    Write-Host "Please build the project first:" -ForegroundColor Red
    Write-Host "  .\build.ps1" -ForegroundColor Yellow
    Write-Host "  OR" -ForegroundColor Gray
    Write-Host "  mvn package" -ForegroundColor Yellow
    exit 1
}

# Check Java
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Java is not installed or not in PATH" -ForegroundColor Red
    exit 1
}

# Get JAR size for info
$jarSize = (Get-Item $JarPath).Length
$jarSizeMB = [math]::Round($jarSize / 1MB, 2)

Write-Host "JAR file: $JarPath ($jarSizeMB MB)" -ForegroundColor Gray
Write-Host "Starting application..." -ForegroundColor Gray

# Run the application
try {
    java -jar $JarPath @args
} catch {
    Write-Host "Error running application: $_" -ForegroundColor Red
    exit 1
}
