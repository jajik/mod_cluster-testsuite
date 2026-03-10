#!/bin/bash

set -e

echo "Rebuilding ModCluster Test Images"
echo "=================================="
echo

# Remove existing images
echo "Removing existing modcluster-test images..."
docker images | grep "modcluster-test" | awk '{print $1":"$2}' | xargs -r docker rmi 2>/dev/null || true

echo

# Run setup to rebuild
echo "Running setup to rebuild images..."
./setup.sh

echo
echo "=================================="
echo "Images rebuilt successfully!"
echo
echo "You can now run tests:"
echo "  mvn test"
echo
