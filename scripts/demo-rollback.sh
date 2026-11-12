#!/usr/bin/env bash
# Rollback demo: deploy the deliberately broken scan-api v3 image, show the new pod failing while
# the old pods keep serving, then `kubectl rollout undo` and show the history.
#
# Usage:
#   scripts/demo-rollback.sh          # pre-flight checks and the plan only; changes nothing
#   scripts/demo-rollback.sh --yes    # run the live demo
#
# Settings (environment variables):
#   BROKEN_IMAGE         default nikitaravindra/scan-api:v3-broken-c1670ef (branch demo/v3-broken:
#                        refuses to start without REQUIRED_RELEASE_TOKEN, which the ConfigMap does
#                        not set; do NOT add that variable, the failure is the point of the demo)
#   ALLOW_MISSING_IMAGE  1 = continue even if the image cannot be found on the registry
#                        (TEAM_PLAN handoff 10 fallback: ImagePullBackOff also stalls the rollout)
#   FAIL_TIMEOUT         seconds to wait for the new pod to fail (default 180)
#   SERVICE_CHECK        1 = prove the old pods still serve, via the NodePort from inside the
#                        Minikube node (default 1; needs the minikube CLI)
#
# The Deployment uses the default RollingUpdate strategy (maxSurge 25%, maxUnavailable 25%). With
# 3 replicas that rounds to 1 surge pod and 0 unavailable, so the 3 healthy pods stay up while the
# broken surge pod fails. If anything goes wrong after the broken image is applied, the script runs
# `kubectl rollout undo` itself before exiting.
set -euo pipefail

NAMESPACE="${NAMESPACE:-phishlens}"
DEPLOYMENT="scan-api"
CONTAINER="scan-api"
BROKEN_IMAGE="${BROKEN_IMAGE:-nikitaravindra/scan-api:v3-broken-c1670ef}"
ALLOW_MISSING_IMAGE="${ALLOW_MISSING_IMAGE:-0}"
FAIL_TIMEOUT="${FAIL_TIMEOUT:-180}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-900s}"   # each scan-api pod needs ~2 min to start on the 2-CPU Minikube
SERVICE_CHECK="${SERVICE_CHECK:-1}"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "=== $(date '+%H:%M:%S') $*"; }
k() { kubectl -n "$NAMESPACE" "$@"; }

confirmed=false
case "${1:-}" in
    --yes) confirmed=true ;;
    "") ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown argument '$1' (use --yes to run the live demo)" ;;
esac

# ---------------------------------------------------------------- pre-flight (read-only)
command -v kubectl >/dev/null || die "kubectl not found on PATH"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace '$NAMESPACE' not found in context '$(kubectl config current-context 2>/dev/null)'. In WSL, kubectl may not point at the Windows Minikube; run this from Git Bash."
k get deployment "$DEPLOYMENT" >/dev/null 2>&1 || die "deployment '$DEPLOYMENT' not found"

current_image=$(k get deployment "$DEPLOYMENT" -o jsonpath="{.spec.template.spec.containers[?(@.name=='$CONTAINER')].image}")
desired=$(k get deployment "$DEPLOYMENT" -o jsonpath='{.spec.replicas}')
ready=$(k get deployment "$DEPLOYMENT" -o jsonpath='{.status.readyReplicas}')
updated=$(k get deployment "$DEPLOYMENT" -o jsonpath='{.status.updatedReplicas}')

step "Current state"
echo "image:    $current_image"
echo "replicas: ${ready:-0} ready / $desired desired"
k get pods -l app=scan-api
echo
k rollout history "deployment/$DEPLOYMENT"

[[ "$current_image" != "$BROKEN_IMAGE" ]] \
    || die "the deployment already runs $BROKEN_IMAGE; recover first with: kubectl -n $NAMESPACE rollout undo deployment/$DEPLOYMENT"
[[ "${ready:-0}" == "$desired" && "${updated:-0}" == "$desired" ]] \
    || die "the current rollout is not healthy (${ready:-0}/$desired ready, ${updated:-0} updated); fix that before the demo"

image_found="unknown"
if command -v docker >/dev/null 2>&1; then
    if docker manifest inspect "$BROKEN_IMAGE" >/dev/null 2>&1; then image_found="yes"; else image_found="no"; fi
fi
echo
echo "Broken image: $BROKEN_IMAGE (found on registry: $image_found)"
if [[ "$image_found" == "no" && "$ALLOW_MISSING_IMAGE" != "1" ]]; then
    die "$BROKEN_IMAGE was not found. Set ALLOW_MISSING_IMAGE=1 to demo with ImagePullBackOff instead (TEAM_PLAN handoff 10)."
fi

node_port=$(k get service scan-api -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)
check_service() {
    [[ "$SERVICE_CHECK" == "1" && -n "$node_port" ]] || return 0
    command -v minikube >/dev/null 2>&1 || { echo "(minikube CLI not found; skipping the service check)"; return 0; }
    local ip
    ip=$(minikube ip 2>/dev/null | tr -d '\r') || return 0
    echo "GET http://$ip:$node_port/api/info (NodePort, sent from inside the Minikube node):"
    minikube ssh -- "curl -s -m 10 -w ' HTTP %{http_code}' http://$ip:$node_port/api/info" || echo "(request failed)"
    echo
}

if ! $confirmed; then
    cat <<EOF

Pre-flight checks passed. Nothing has been changed.

The live demo will:
  1. kubectl -n $NAMESPACE set image deployment/$DEPLOYMENT $CONTAINER=$BROKEN_IMAGE
     and record a change-cause annotation on the new revision
  2. wait up to ${FAIL_TIMEOUT}s for the new pod to reach CrashLoopBackOff / ImagePullBackOff
  3. show pods, the stalled rollout, the broken pod's log, and that $ready old pods still serve
  4. kubectl -n $NAMESPACE rollout undo deployment/$DEPLOYMENT, wait for it, show the history

Re-run with --yes to perform it.
EOF
    exit 0
fi

# ---------------------------------------------------------------- live demo
broken_applied=false
undone=false
recover() {
    local rc=$?
    if $broken_applied && ! $undone; then
        echo >&2
        echo "Demo interrupted after the broken image was applied; rolling back now." >&2
        k rollout undo "deployment/$DEPLOYMENT" >&2 || true
        k rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT" >&2 || \
            echo "Check manually: kubectl -n $NAMESPACE rollout status deployment/$DEPLOYMENT" >&2
    fi
    exit "$rc"
}
trap recover EXIT
trap 'exit 130' INT TERM

step "Deploying the broken v3 image"
k set image "deployment/$DEPLOYMENT" "$CONTAINER=$BROKEN_IMAGE"
broken_applied=true
k annotate "deployment/$DEPLOYMENT" --overwrite \
    "kubernetes.io/change-cause=rollback demo: deploy broken v3 ($BROKEN_IMAGE)"

new_rs=""
for _ in $(seq 1 30); do
    revision=$(k get deployment "$DEPLOYMENT" -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')
    new_rs=$(k get rs -l app=scan-api -o jsonpath="{range .items[?(@.metadata.annotations.deployment\.kubernetes\.io/revision=='$revision')]}{.metadata.labels.pod-template-hash}{end}")
    [[ -n "$new_rs" ]] && break
    sleep 1
done
[[ -n "$new_rs" ]] || die "could not find the new ReplicaSet"
echo "New ReplicaSet pod-template-hash: $new_rs (revision $revision)"

step "Waiting up to ${FAIL_TIMEOUT}s for the new pod to fail"
failure=""
deadline=$((SECONDS + FAIL_TIMEOUT))
while (( SECONDS < deadline )); do
    reasons=$(k get pods -l "app=scan-api,pod-template-hash=$new_rs" \
        -o jsonpath='{range .items[*]}{.status.containerStatuses[0].state.waiting.reason}{" "}{.status.containerStatuses[0].restartCount}{"\n"}{end}' 2>/dev/null || true)
    if grep -Eq 'CrashLoopBackOff|ImagePullBackOff|ErrImagePull' <<<"$reasons"; then
        failure=$(grep -Eo 'CrashLoopBackOff|ImagePullBackOff|ErrImagePull' <<<"$reasons" | head -1)
        break
    fi
    sleep 5
done
k get pods -l app=scan-api -o wide
[[ -n "$failure" ]] || die "the new pod did not reach a failure state within ${FAIL_TIMEOUT}s (rolling back)"
echo
echo "New pod is in $failure."

step "The rollout is stalled (a timeout here is expected)"
k rollout status "deployment/$DEPLOYMENT" --timeout=10s || echo "-> rollout did not complete, as expected"
k get deployment "$DEPLOYMENT"
k get rs -l app=scan-api -o wide

if [[ "$failure" == "CrashLoopBackOff" ]]; then
    step "Why the v3 pod fails (last lines of its previous container log)"
    broken_pod=$(k get pods -l "app=scan-api,pod-template-hash=$new_rs" -o jsonpath='{.items[0].metadata.name}')
    k logs "$broken_pod" --previous --tail=200 2>/dev/null | grep -E 'REQUIRED_RELEASE_TOKEN|APPLICATION FAILED|Reason:|Action:' | head -10 \
        || echo "(log not available yet)"
fi

step "The old pods are still serving"
old_ready=$(k get deployment "$DEPLOYMENT" -o jsonpath='{.status.readyReplicas}')
echo "Ready replicas during the failed rollout: ${old_ready:-0}"
check_service

step "Rolling back: kubectl rollout undo"
k rollout undo "deployment/$DEPLOYMENT"
k rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT"
undone=true

step "Recovered state"
echo "image: $(k get deployment "$DEPLOYMENT" -o jsonpath="{.spec.template.spec.containers[?(@.name=='$CONTAINER')].image}")"
k get pods -l app=scan-api -o wide
echo
k rollout history "deployment/$DEPLOYMENT"
check_service

echo
echo "Rollback demo completed."
