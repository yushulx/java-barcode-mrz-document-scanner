#!/bin/bash
# Run LiteCam Barcode Scanner
# Shell script to run the Maven-built application

set -e

JAR_PATH="target/litecam-barcode-scanner-1.0.0.jar"

echo "Starting LiteCam Barcode Scanner..."

# Check if JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR file not found at $JAR_PATH" >&2
    echo "Please build the project first:" >&2
    echo "  ./build.sh" >&2
    echo "  OR" >&2
    echo "  mvn package" >&2
    exit 1
fi

# Check Java
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not found in PATH" >&2
    echo "Please install Java and make sure it's accessible from PATH." >&2
    exit 1
fi

# Get JAR size for info
if command -v stat &> /dev/null; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS stat command
        jar_size=$(stat -f%z "$JAR_PATH" 2>/dev/null || echo "0")
    else
        # Linux stat command
        jar_size=$(stat -c%s "$JAR_PATH" 2>/dev/null || echo "0")
    fi
    jar_size_mb=$(echo "scale=2; $jar_size / 1048576" | bc 2>/dev/null || echo "unknown")
    echo "JAR file: $JAR_PATH (${jar_size_mb} MB)"
else
    echo "JAR file: $JAR_PATH"
fi

echo "Starting application..."

# Run the application
java -jar "$JAR_PATH" "$@"
