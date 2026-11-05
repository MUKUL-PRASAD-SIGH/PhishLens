#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="phishlens"
DEPLOYMENT="scan-api"

scale_replicas() {
    local count="$1"

    echo
    echo "Scaling scan-api to ${count} replicas..."

    kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas="$count"
    kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=180s
    kubectl -n "$NAMESPACE" get pods -l app=scan-api
}

echo "Starting PhishLens scaling demo"
echo "Ensure HPA is temporarily removed before manual scaling."

scale_replicas 3
scale_replicas 4
scale_replicas 3

echo
echo "Scaling demo completed successfully."
