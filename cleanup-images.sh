#!/bin/bash

set -e

echo "ModCluster Test Image Cleanup"
echo "=============================="
echo

# List all modcluster-test images
echo "Current modcluster-test images:"
docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}" | grep "modcluster-test" || echo "No modcluster-test images found"

echo
echo "Options:"
echo "  1) Remove all modcluster-test images"
echo "  2) Remove specific image"
echo "  3) Keep images (exit)"
echo

read -p "Choose option [1-3]: " choice

case $choice in
    1)
        echo "Removing all modcluster-test images..."
        docker images | grep "modcluster-test" | awk '{print $1":"$2}' | xargs -r docker rmi
        echo "Done!"
        ;;
    2)
        read -p "Enter image tag to remove (e.g., modcluster-test/wildfly-39-0-1-final:openjdk-17): " image_tag
        echo "Removing $image_tag..."
        docker rmi "$image_tag"
        echo "Done!"
        ;;
    3)
        echo "Keeping images. Exiting."
        exit 0
        ;;
    *)
        echo "Invalid option. Exiting."
        exit 1
        ;;
esac

echo
echo "Remaining images:"
docker images | grep "modcluster-test" || echo "No modcluster-test images remaining"
