#!/bin/bash
# Build script for the LiteCam MRZ Scanner example

set -e

echo "Building LiteCam MRZ Scanner Maven Example..."
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
echo "Installing LiteCam into the local Maven repository..."
mvn -q install:install-file \
    -Dfile=libs/litecam.jar \
    -DgroupId=com.example \
    -DartifactId=litecam \
    -Dversion=1.0.0 \
    -Dpackaging=jar

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
echo "  Option 1: mvn exec:java -Dexec.mainClass=\"com.example.litecam.MRZScanner\""
echo "  Option 2: java -jar target/litecam-mrz-scanner-1.0.0.jar"
echo ""
echo "JAR file created: target/litecam-mrz-scanner-1.0.0.jar"
echo "JAR size: $(du -h target/litecam-mrz-scanner-1.0.0.jar 2>/dev/null | cut -f1 || echo 'Unknown')"
