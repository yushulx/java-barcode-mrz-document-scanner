# Build script for LiteCam Barcode Scanner Maven Example
# PowerShell version

$ErrorActionPreference = "Stop"

# Define Maven executable path
$MavenPath = "C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin\mvn.cmd"

Write-Host "Building LiteCam Document Scanner Maven Example..." -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green

# Check if Maven is available at the expected path
if (-not (Test-Path $MavenPath)) {
    # Try to find Maven in PATH
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Host "Error: Maven is not installed or not found" -ForegroundColor Red
        Write-Host "Expected Maven location: $MavenPath" -ForegroundColor Red
        exit 1
    } else {
        $MavenPath = "mvn"
    }
}

# Check if Java is installed
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Java is not installed or not in PATH" -ForegroundColor Red
    exit 1
}

# Verify litecam.jar exists
if (-not (Test-Path "libs\litecam.jar")) {
    Write-Host "Error: litecam.jar not found in libs\ directory" -ForegroundColor Red
    Write-Host "Please copy litecam.jar to libs\ directory first" -ForegroundColor Red
    exit 1
}

Write-Host "`nJava version:" -ForegroundColor Yellow
java -version

Write-Host "`nMaven version:" -ForegroundColor Yellow
& $MavenPath -version

Write-Host "`nCleaning previous build..." -ForegroundColor Yellow
& $MavenPath clean

Write-Host "`nCompiling project..." -ForegroundColor Yellow
& $MavenPath compile

Write-Host "`nRunning tests..." -ForegroundColor Yellow
& $MavenPath test

Write-Host "`nCreating fat JAR with dependencies..." -ForegroundColor Yellow
& $MavenPath package

Write-Host "`nBuild completed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "To run the application:"
Write-Host "  Option 1: & `"$MavenPath`" exec:java -Dexec.mainClass=`"com.example.litecam.MRZScanner`""
Write-Host "  Option 2: java -jar target\litecam-mrz-scanner-1.0.0.jar"
Write-Host ""

$jarPath = "target\litecam-mrz-scanner-1.0.0.jar"
if (Test-Path $jarPath) {
    $jarSize = (Get-Item $jarPath).Length
    $jarSizeMB = [math]::Round($jarSize / 1MB, 2)
    Write-Host "JAR file created: $jarPath"
    Write-Host "JAR size: $jarSizeMB MB"
} else {
    Write-Host "Warning: JAR file not found at expected location" -ForegroundColor Yellow
}
