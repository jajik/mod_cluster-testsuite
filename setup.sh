#!/bin/bash

set -e

echo "ModCluster Test Suite - Setup Script"
echo "======================================"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Please install Java 11 or higher."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "❌ Java 11 or higher is required. Found: Java $JAVA_VERSION"
    exit 1
fi
echo "✓ Java $JAVA_VERSION found"

# Check for Maven
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven not found. Please install Maven 3.6 or higher."
    exit 1
fi
echo "✓ Maven found"

# Check for Docker/Podman
if command -v docker &> /dev/null; then
    echo "✓ Docker found"
    CONTAINER_CMD="docker"
elif command -v podman &> /dev/null; then
    echo "✓ Podman found"
    CONTAINER_CMD="podman"
else
    echo "❌ Neither Docker nor Podman found. Please install one of them."
    exit 1
fi

# Check if container engine is running
if ! $CONTAINER_CMD ps &> /dev/null; then
    echo "❌ Container engine is not running. Please start Docker/Podman."
    exit 1
fi
echo "✓ Container engine is running"

# Check for WildFly/EAP ZIP
echo ""
echo "Checking for WildFly/EAP distributions..."

if [ -d "distributions" ] && [ "$(ls -A distributions/*.zip 2>/dev/null)" ]; then
    ZIP_COUNT=$(ls -1 distributions/*.zip 2>/dev/null | wc -l)
    echo "✓ Found $ZIP_COUNT ZIP distribution(s):"
    ls -1 distributions/*.zip 2>/dev/null | while read zip; do
        SIZE=$(du -h "$zip" | cut -f1)
        echo "  - $(basename $zip) ($SIZE)"
    done
else
    echo "⚠️  No ZIP distributions found in distributions/"
    echo ""
    echo "To use custom WildFly/EAP distributions:"
    echo "  1. Download WildFly: https://www.wildfly.org/downloads/"
    echo "  2. Or get EAP from: https://access.redhat.com/"
    echo "  3. Place ZIP in: distributions/"
    echo ""
    echo "Tests will use pre-built container images as fallback."
fi

echo ""
echo "======================================"
echo "Setup complete! You can now run tests:"
echo ""
echo "  # Run all tests with undertow balancer"
echo "  mvn test"
echo ""
echo "  # Run with httpd balancer"
echo "  mvn test -Phttpd"
echo ""
echo "  # Run specific test"
echo "  mvn test -Dtest=StickySessionTest"
echo ""
echo "  # Use specific ZIP"
echo "  mvn test -Dwildfly.zip.path=/path/to/wildfly.zip"
echo ""
