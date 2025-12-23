#!/bin/bash

# Script to build Java app and push Docker image to OCIR
# Usage: ./build-and-push.sh <VERSION>

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
APP_NAME="my-app-name"
OCIR_REGION="gru.ocir.io"
OCIR_NAMESPACE="<namespace-ocir>"
OCIR_REPO="<repo-ocir>"

# Check if version argument is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: VERSION argument is required${NC}"
    echo "Usage: $0 <VERSION>"
    echo "Example: $0 1.0.0"
    exit 1
fi

VERSION=$1

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Building and Pushing Docker Image${NC}"
echo -e "${GREEN}Version: ${VERSION}${NC}"
echo -e "${GREEN}========================================${NC}"


# Step 2: Build Docker image
echo -e "\n${YELLOW}Step 2: Building Docker image...${NC}"
docker build -t ${APP_NAME}:${VERSION} .
echo -e "${GREEN}✓ Docker image built successfully${NC}"

# Step 3: Get the image ID
echo -e "\n${YELLOW}Step 3: Getting image ID...${NC}"
IMAGE_ID=$(sudo docker images ${APP_NAME}:${VERSION} --format "{{.ID}}" | head -n 1)

if [ -z "$IMAGE_ID" ]; then
    echo -e "${RED}Error: Could not find image ID for ${APP_NAME}:${VERSION}${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Image ID: ${IMAGE_ID}${NC}"

# Step 4: Tag the image for OCIR
echo -e "\n${YELLOW}Step 4: Tagging image for OCIR...${NC}"
OCIR_TAG="${OCIR_REGION}/${OCIR_NAMESPACE}/${OCIR_REPO}:${VERSION}"
docker tag ${IMAGE_ID} ${OCIR_TAG}
echo -e "${GREEN}✓ Image tagged as: ${OCIR_TAG}${NC}"

# Step 5: Push to OCIR
echo -e "\n${YELLOW}Step 5: Pushing image to OCIR...${NC}"
docker push ${OCIR_TAG}
echo -e "${GREEN}✓ Image pushed successfully${NC}"

# Summary
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}✓ Build and Push Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "Image: ${OCIR_TAG}"
echo -e "Image ID: ${IMAGE_ID}"
echo -e "${GREEN}========================================${NC}"