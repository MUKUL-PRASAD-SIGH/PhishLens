#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="phishlens"
DEPLOYMENT="scan-api"

echo "Current replicas:"
kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT"

echo
echo "Scaling scan-api to 3 replicas..."
kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas=3
kubectl -n "$NAMESPACE" rollout status deployment/"$DEPLOYMENT" --timeout=180s
kubectl -n "$NAMESPACE" get pods -l app=scan-api

echo
echo "Scaling scan-api to 5 replicas..."
kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas=5
kubectl -n "$NAMESPACE" rollout status deployment/"$DEPLOYMENT" --timeout=180s
kubectl -n "$NAMESPACE" get pods -l app=scan-api

echo
echo "Scaling scan-api down to 2 replicas..."
kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas=2
kubectl -n "$NAMESPACE" rollout status deployment/"$DEPLOYMENT" --timeout=180s
kubectl -n "$NAMESPACE" get pods -l app=scan-api

echo
echo "Scaling demo complete."
