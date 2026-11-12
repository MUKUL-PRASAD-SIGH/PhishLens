#!/usr/bin/env bash
# Read-only evidence capture for the Kubernetes deployment. Every file holds the exact command,
# the time it ran, and its unedited output. Nothing in the cluster is changed; the two scan
# requests only add entries to scan-api's in-memory history.
#
# Usage:
#   scripts/capture-evidence.sh                 # writes to evidence/k8s/text-<timestamp>/
#   OUT_DIR=some/dir scripts/capture-evidence.sh
#
# The NodePort requests are sent from inside the Minikube node (minikube ssh) because, with the
# Docker driver on Windows, the host cannot reach the node IP directly.
set -uo pipefail

NAMESPACE="${NAMESPACE:-phishlens}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/../evidence/k8s/text-$(date +%Y%m%d-%H%M%S)}"
SUSPICIOUS_URL="${SUSPICIOUS_URL:-http://192.168.4.7/secure-update/login.php?account=verify&id=88231}"
BORDERLINE_URL="${BORDERLINE_URL:-http://shop.example.net/item?id=123456789}"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || { echo "ERROR: namespace '$NAMESPACE' not found in the current kubectl context" >&2; exit 1; }
mkdir -p "$OUT_DIR"

# capture <file> <command...>: runs the command and records it with its output.
capture() {
    local file="$OUT_DIR/$1"; shift
    {
        echo "\$ $*"
        echo "# captured $(date '+%Y-%m-%d %H:%M:%S %z')"
        "$@" 2>&1
        echo "# exit code: $?"
    } > "$file"
    echo "wrote $file"
}

k() { kubectl -n "$NAMESPACE" "$@"; }

capture 01-get-all.txt              kubectl -n "$NAMESPACE" get deployments,replicasets,pods,services,hpa -o wide
capture 02-describe-deploy-scan-api.txt   kubectl -n "$NAMESPACE" describe deployment scan-api
capture 03-describe-deploy-risk-model.txt kubectl -n "$NAMESPACE" describe deployment risk-model
pod=$(k get pods -l app=scan-api -o jsonpath='{.items[0].metadata.name}')
capture 04-describe-pod-scan-api.txt      kubectl -n "$NAMESPACE" describe pod "$pod"
capture 05-configmap.txt            kubectl -n "$NAMESPACE" get configmap phishlens-config -o yaml
capture 06-pod-env.txt              kubectl -n "$NAMESPACE" exec "$pod" -- printenv SERVER_PORT MODEL_URL RISK_THRESHOLD MODEL_TIMEOUT_MS JAVA_TOOL_OPTIONS
capture 07-rollout-history-scan-api.txt   kubectl -n "$NAMESPACE" rollout history deployment/scan-api
capture 08-rollout-history-risk-model.txt kubectl -n "$NAMESPACE" rollout history deployment/risk-model
capture 09-replicaset-revisions.txt kubectl -n "$NAMESPACE" get replicasets -o "custom-columns=NAME:.metadata.name,REVISION:.metadata.annotations.deployment\.kubernetes\.io/revision,CREATED:.metadata.creationTimestamp,DESIRED:.spec.replicas,READY:.status.readyReplicas,IMAGE:.spec.template.spec.containers[0].image"
capture 10-hpa.txt                  kubectl -n "$NAMESPACE" describe hpa scan-api-hpa
capture 11-top.txt                  bash -c "kubectl top nodes; echo; kubectl -n $NAMESPACE top pods"
capture 12-metrics-server.txt       kubectl -n kube-system get deployment metrics-server
capture 13-events.txt               kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp

if command -v minikube >/dev/null 2>&1; then
    ip=$(minikube ip | tr -d '\r')
    port=$(k get service scan-api -o jsonpath='{.spec.ports[0].nodePort}')
    capture 14-minikube-node-limits.txt docker inspect minikube --format 'NanoCpus={{.HostConfig.NanoCpus}} MemoryBytes={{.HostConfig.Memory}}'
    capture 15-nodeport-info-health.txt minikube ssh -- "curl -s -m 15 -w ' HTTP %{http_code}\n' http://$ip:$port/api/info; curl -s -m 15 -w ' HTTP %{http_code}\n' http://$ip:$port/actuator/health"
    for name in suspicious borderline; do
        url=$SUSPICIOUS_URL; [[ $name == borderline ]] && url=$BORDERLINE_URL
        capture "16-nodeport-scan-$name.txt" minikube ssh -- "curl -s -m 20 -w ' HTTP %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{\"url\":\"$url\"}' http://$ip:$port/api/scan"
    done
    capture 17-host-to-nodeport.txt curl -s -m 5 -w ' HTTP %{http_code}\n' "http://$ip:$port/api/info"
fi

echo "Done: $OUT_DIR"
