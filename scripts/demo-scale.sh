#!/usr/bin/env bash
# Manual scaling demo for the scan-api Deployment.
#
# Usage:
#   scripts/demo-scale.sh                      # default sequence 3 -> 4 -> 3 (the sequence demonstrated)
#   scripts/demo-scale.sh 3 5 2                # the TEAM_PLAN sequence, if the cluster has room for 5 pods
#   scripts/demo-scale.sh --suspend-hpa 3 4 3  # delete the HPA for the demo, re-apply k8s/hpa.yaml afterwards
#
# Why the default is 3 -> 4 -> 3: the Minikube container on the demo laptop is capped at
# 2 CPUs / 2 GiB by Docker, while the node advertises more to the scheduler. A 5th scan-api pod
# (384Mi request, 512Mi limit) does not fit in 2 GiB alongside the rest of the stack.
#
# The HPA (k8s/hpa.yaml, minReplicas 3) overrides `kubectl scale`, so this script refuses to run
# while the HPA exists unless --suspend-hpa is given. Record this demo separately from the HPA demo.
#
# Run it where kubectl points at the Minikube cluster (Git Bash or PowerShell on Windows; in WSL
# kubectl may use a different kubeconfig).
set -euo pipefail

NAMESPACE="${NAMESPACE:-phishlens}"
DEPLOYMENT="scan-api"
HPA_NAME="scan-api-hpa"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HPA_MANIFEST="${HPA_MANIFEST:-$SCRIPT_DIR/../k8s/hpa.yaml}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-600s}"   # a new scan-api pod needs ~2 min to start on the 2-CPU Minikube

die() { echo "ERROR: $*" >&2; exit 1; }

suspend_hpa=false
sequence=()
for arg in "$@"; do
    case "$arg" in
        --suspend-hpa) suspend_hpa=true ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        ''|*[!0-9]*) die "replica counts must be whole numbers, got '$arg'" ;;
        *) sequence+=("$arg") ;;
    esac
done
[[ ${#sequence[@]} -eq 0 ]] && sequence=(3 4 3)

command -v kubectl >/dev/null || die "kubectl not found on PATH"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace '$NAMESPACE' not found in context '$(kubectl config current-context 2>/dev/null)'. In WSL, kubectl may not point at the Windows Minikube; run this from Git Bash or PowerShell."
kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" >/dev/null 2>&1 || die "deployment '$DEPLOYMENT' not found"

hpa_removed=false
restore_hpa() {
    if $hpa_removed; then
        echo
        echo "Re-applying the HPA from $HPA_MANIFEST ..."
        kubectl apply -f "$HPA_MANIFEST" || echo "WARNING: could not re-apply the HPA; run: kubectl apply -f k8s/hpa.yaml" >&2
    fi
}
trap restore_hpa EXIT

if kubectl -n "$NAMESPACE" get hpa "$HPA_NAME" >/dev/null 2>&1; then
    if ! $suspend_hpa; then
        die "HPA '$HPA_NAME' exists and would override manual scaling (minReplicas 3). Re-run with --suspend-hpa, or delete it first: kubectl -n $NAMESPACE delete hpa $HPA_NAME"
    fi
    [[ -f "$HPA_MANIFEST" ]] || die "cannot suspend the HPA: $HPA_MANIFEST not found, so it could not be restored"
    echo "Suspending HPA '$HPA_NAME' for the manual scaling demo (it is re-applied at the end)."
    kubectl -n "$NAMESPACE" delete hpa "$HPA_NAME"
    hpa_removed=true
fi

show_state() {
    local ready desired
    desired=$(kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" -o jsonpath='{.spec.replicas}')
    ready=$(kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" -o jsonpath='{.status.readyReplicas}')
    echo "scan-api ready replicas: ${ready:-0}/${desired}"
    kubectl -n "$NAMESPACE" get pods -l app=scan-api -o wide
}

scale_replicas() {
    local count="$1"
    echo
    echo "=== $(date '+%H:%M:%S') Scaling scan-api to ${count} replicas"
    kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas="$count"
    if ! kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT"; then
        echo
        echo "Rollout did not finish within $ROLLOUT_TIMEOUT. Pods that are not Running:" >&2
        kubectl -n "$NAMESPACE" get pods -l app=scan-api --field-selector=status.phase!=Running >&2 || true
        echo "Recent scheduling events (look for FailedScheduling / Insufficient memory or cpu):" >&2
        kubectl -n "$NAMESPACE" get events --field-selector=reason=FailedScheduling \
            --sort-by=.lastTimestamp 2>/dev/null | tail -5 >&2 || true
        die "scaling to $count did not complete; scale back with: kubectl -n $NAMESPACE scale deployment $DEPLOYMENT --replicas=3"
    fi
    show_state
}

echo "PhishLens manual scaling demo: ${sequence[*]} (namespace $NAMESPACE)"
echo
echo "=== Starting state"
show_state

for count in "${sequence[@]}"; do
    scale_replicas "$count"
done

echo
echo "Scaling demo completed: ${sequence[*]}."
