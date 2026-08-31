#!/usr/bin/env bash
set -e

# Configuration variables - update these as needed
export PROJECT_ID=$(gcloud config get-value project)
export REGION="us-central1"
export REPO_NAME="storeops-repo"
export IMAGE_NAME="storeops-api"
export CLUSTER_NAME="storeops-gke-cluster"
export IMAGE_TAG="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME/$IMAGE_NAME:v1"

echo "==> Creating Artifact Registry repository (if not exists)..."
gcloud artifacts repositories create $REPO_NAME \
    --repository-format=docker \
    --location=$REGION || true

echo "==> Authenticating Docker with GCP..."
gcloud auth configure-docker $REGION-docker.pkg.dev --quiet

echo "==> Building Docker image using multi-stage build..."
docker build -t $IMAGE_TAG .

echo "==> Pushing Image to Artifact Registry..."
docker push $IMAGE_TAG

echo "==> Creating GKE Autopilot Cluster (if not exists)..."
gcloud container clusters create-auto $CLUSTER_NAME --region $REGION || true

echo "==> Getting GKE credentials..."
gcloud container clusters get-credentials $CLUSTER_NAME --region $REGION

echo "==> Updating image reference in deployment manifest..."
sed -i "s|LOCATION-docker.pkg.dev/PROJECT_ID/REPO_NAME/storeops-api:v1|$IMAGE_TAG|g" k8s/deployment.yaml

echo "==> Deploying to GKE cluster..."
kubectl apply -f k8s/deployment.yaml

echo "==> Fetching service details..."
kubectl get svc storeops-api-service