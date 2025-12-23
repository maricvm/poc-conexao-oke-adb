#!/bin/bash

# Script to deploy new version to Kubernetes
# Usage: ./deploy.sh <VERSION>

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
POD_NAME="java-teste"
NAMESPACE="<namesace-k8s>"
YAML_FILE="pod.yaml"
OCIR_IMAGE="gru.ocir.io/<namespace-ocir>/<repo-ocir>"

# Check if version argument is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: VERSION argument is required${NC}"
    echo "Usage: $0 <VERSION>"
    echo "Example: $0 1.0.0"
    exit 1
fi

VERSION=$1

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deploying to Kubernetes${NC}"
echo -e "${GREEN}Version: ${VERSION}${NC}"
echo -e "${GREEN}========================================${NC}"

# Step 1: Delete existing pod
echo -e "\n${YELLOW}Step 1: Deleting existing pod...${NC}"
if kubectl get pod ${POD_NAME} -n ${NAMESPACE} &> /dev/null; then
    kubectl delete pod ${POD_NAME} -n ${NAMESPACE}
    echo -e "${GREEN}✓ Pod deleted successfully${NC}"
    
    # Wait for pod to be fully deleted
    echo -e "${YELLOW}Waiting for pod to terminate...${NC}"
    kubectl wait --for=delete pod/${POD_NAME} -n ${NAMESPACE} --timeout=60s 2>/dev/null || true
else
    echo -e "${YELLOW}⚠ Pod not found (might be first deployment)${NC}"
fi

# Step 2: Update YAML file with new image tag
echo -e "\n${YELLOW}Step 2: Updating YAML file with version ${VERSION}...${NC}"

cat > ${YAML_FILE} << EOF
apiVersion: v1
kind: Pod
metadata:
  name: java-teste
  namespace: teste
spec:
  serviceAccountName: testeserviceaccount
  automountServiceAccountToken: true
  hostNetwork: true
  containers:
    - name: ngnix
      image: ${OCIR_IMAGE}:${VERSION}
      imagePullPolicy: Always
      ports:
      - name: nginx
        containerPort: 8080
        protocol: TCP
  imagePullSecrets:
    - name: ocirsecret
EOF

echo -e "${GREEN}✓ YAML file updated with image tag: ${VERSION}${NC}"

# Step 3: Apply the updated YAML
echo -e "\n${YELLOW}Step 3: Applying configuration to Kubernetes...${NC}"
kubectl apply -f ${YAML_FILE}
echo -e "${GREEN}✓ Configuration applied successfully${NC}"

# Step 4: Wait for pod to be ready
echo -e "\n${YELLOW}Step 4: Waiting for pod to be ready...${NC}"
kubectl wait --for=condition=Ready pod/${POD_NAME} -n ${NAMESPACE} --timeout=120s

# Show pod status
echo -e "\n${YELLOW}Pod Status:${NC}"
kubectl get pod ${POD_NAME} -n ${NAMESPACE}

# Summary
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}✓ Deployment Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "Pod: ${POD_NAME}"
echo -e "Namespace: ${NAMESPACE}"
echo -e "Image: ${OCIR_IMAGE}:${VERSION}"
echo -e "${GREEN}========================================${NC}"

# Show logs hint
echo -e "\n${YELLOW}App logs"
kubectl logs ${POD_NAME} -n ${NAMESPACE}