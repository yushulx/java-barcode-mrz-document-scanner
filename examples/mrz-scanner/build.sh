#!/bin/bash
# Build script for LiteCam Barcode Scanner Maven Example

set -e

echo "Building LiteCam Barcode Scanner Maven Example..."
echo "================================================="

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed or not in PATH"
    exit 1
fi

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not in PATH"
    exit 1
fi

# Verify litecam.jar exists
if [ ! -f "libs/litecam.jar" ]; then
    echo "Error: litecam.jar not found in libs/ directory"
    echo "Please copy litecam.jar to libs/ directory first"
    exit 1
fi

echo "Java version:"
java -version

echo ""
echo "Maven version:"
mvn -version

echo ""
echo "Cleaning previous build..."
mvn clean

echo ""
echo "Compiling project..."
mvn compile

echo ""
echo "Running tests..."
mvn test

echo ""
echo "Creating fat JAR with dependencies..."
mvn package

echo ""
echo "Build completed successfully!"
echo ""
echo "To run the application:"
echo "  Option 1: mvn exec:java -Dexec.mainClass=\"com.example.litecam.BarcodeScanner\""
echo "  Option 2: java -jar target/litecam-barcode-scanner-1.0-SNAPSHOT-shaded.jar"
echo ""
echo "JAR file created: target/litecam-barcode-scanner-1.0-SNAPSHOT-shaded.jar"
echo "JAR size: $(du -h target/litecam-barcode-scanner-1.0-SNAPSHOT-shaded.jar 2>/dev/null | cut -f1 || echo 'Unknown')"
