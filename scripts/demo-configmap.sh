#!/usr/bin/env bash
# ConfigMap demo: lower RISK_THRESHOLD from 0.70 to 0.60, restart scan-api, and show a borderline
# URL changing verdict from SUSPICIOUS to MALICIOUS with no code or image change. The original
# threshold is restored at the end (and on any error).
#
# Usage:
#   scripts/demo-configmap.sh          # pre-flight: scores the test URL, checks it is borderline; changes nothing
#   scripts/demo-configmap.sh --yes    # run the live demo, then restore the original threshold
#
# Settings (environment variables):
#   NEW_THRESHOLD  default 0.60
#   TEST_URL       default http://shop.example.net/item?id=123456789
#                  (scored 0.64 by risk-model v2 on 2026-11-05; http://login.bank-update.info/session?id=12345
#                  scored 0.67 and is a second choice)
#   TARGET_URL     scan-api base URL, e.g. from `minikube service scan-api -n phishlens --url`.
#                  If unset, requests go to <minikube ip>:<nodePort> from inside the Minikube node.
#   KEEP=1         do not restore the original threshold at the end
#
# Why the verdict changes: VerdictPolicy returns MALICIOUS when score >= RISK_THRESHOLD and
# SUSPICIOUS when 0.40 <= score < RISK_THRESHOLD. A model score in [0.60, 0.70) is SUSPICIOUS at
# 0.70 and MALICIOUS at 0.60. The script checks this with the live score before changing anything,
# and only accepts scores with "source":"model" (a fallback score would use different rules).
#
# scan-api reads RISK_THRESHOLD once at startup (envFrom the ConfigMap), so a rollout restart is
# needed after the ConfigMap changes. The restart surges one extra pod at a time (384Mi request).
# On the 2-CPU Minikube each new scan-api pod needs about 2 minutes to pass its startup probe, so
# one restart of 3 pods took about 8 minutes on 2026-11-05; ROLLOUT_TIMEOUT defaults to 900s.
set -euo pipefail

NAMESPACE="${NAMESPACE:-phishlens}"
DEPLOYMENT="scan-api"
CONFIGMAP="phishlens-config"
NEW_THRESHOLD="${NEW_THRESHOLD:-0.60}"
TEST_URL="${TEST_URL:-http://shop.example.net/item?id=123456789}"
KEEP="${KEEP:-0}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-900s}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/../evidence/k8s}"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "=== $(date '+%H:%M:%S') $*"; }
k() { kubectl -n "$NAMESPACE" "$@"; }
field() { tr -d '\r' | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }

confirmed=false
case "${1:-}" in
    --yes) confirmed=true ;;
    "") ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) die "unknown argument '$1' (use --yes to run the live demo)" ;;
esac

command -v kubectl >/dev/null || die "kubectl not found on PATH"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace '$NAMESPACE' not found in context '$(kubectl config current-context 2>/dev/null)'. In WSL, kubectl may not point at the Windows Minikube; run this from Git Bash."
k get configmap "$CONFIGMAP" >/dev/null 2>&1 || die "ConfigMap '$CONFIGMAP' not found"
k get deployment "$DEPLOYMENT" >/dev/null 2>&1 || die "deployment '$DEPLOYMENT' not found"

ORIGINAL=$(k get configmap "$CONFIGMAP" -o jsonpath='{.data.RISK_THRESHOLD}')
[[ -n "$ORIGINAL" ]] || die "RISK_THRESHOLD is not set in $CONFIGMAP"
[[ "$ORIGINAL" != "$NEW_THRESHOLD" ]] || die "RISK_THRESHOLD is already $NEW_THRESHOLD; restore it first: kubectl -n $NAMESPACE patch configmap $CONFIGMAP --type merge -p '{\"data\":{\"RISK_THRESHOLD\":\"0.70\"}}'"

if [[ -n "${TARGET_URL:-}" ]]; then
    TARGET_URL="${TARGET_URL%/}"
    route="$TARGET_URL (from this machine)"
else
    command -v minikube >/dev/null || die "set TARGET_URL, or install the minikube CLI so requests can be sent from inside the node"
    node_ip=$(minikube ip | tr -d '\r')
    node_port=$(k get service scan-api -o jsonpath='{.spec.ports[0].nodePort}')
    route="http://$node_ip:$node_port (NodePort, sent from inside the Minikube node)"
fi

scan_once() {
    local body="{\"url\":\"$TEST_URL\"}"
    if [[ -n "${TARGET_URL:-}" ]]; then
        curl -s -m 20 -X POST -H 'Content-Type: application/json' -d "$body" "$TARGET_URL/api/scan"
    else
        minikube ssh -- "curl -s -m 20 -X POST -H 'Content-Type: application/json' -d '$body' http://$node_ip:$node_port/api/scan"
    fi
}

# Scans until a model-scored answer comes back (the first request after a restart can time out and
# fall back). Prints the JSON and sets SCORE and VERDICT.
scan_model() {
    local resp source
    for attempt in 1 2 3 4 5; do
        resp=$(scan_once || true)
        source=$(field source <<<"$resp")
        if [[ "$source" == "model" ]]; then
            echo "$resp"
            SCORE=$(field score <<<"$resp")
            VERDICT=$(field verdict <<<"$resp")
            return 0
        fi
        echo "attempt $attempt: source='${source:-none}', retrying in 5s ($resp)" >&2
        sleep 5
    done
    return 1
}

# Prints the RISK_THRESHOLD each running, non-terminating scan-api pod sees, e.g. "0.70 0.70 0.70".
current_threshold() {
    local pods p values=""
    pods=$(k get pods -l app=scan-api \
        -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{" "}{.metadata.deletionTimestamp}{"\n"}{end}' \
        2>/dev/null | awk 'NF == 1 { print $1 }')
    for p in $pods; do
        values+="$(k exec "$p" -- printenv RISK_THRESHOLD 2>/dev/null | tr -d '\r' || echo '?') "
    done
    echo "${values:-?}"
}

restart_and_wait() {
    k rollout restart "deployment/$DEPLOYMENT"
    k rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT"
}

step "Before: RISK_THRESHOLD=$ORIGINAL in the ConfigMap; scan-api pods see $(current_threshold)"
echo "POST /api/scan via $route"
echo "url: $TEST_URL"
scan_model || die "no model-scored response after 5 attempts; check risk-model before the demo"
before_verdict="$VERDICT"

in_window=$(awk -v s="$SCORE" -v lo="$NEW_THRESHOLD" -v hi="$ORIGINAL" 'BEGIN { print (s >= lo && s < hi) ? 1 : 0 }')
[[ "$in_window" == "1" ]] \
    || die "score $SCORE is not in [$NEW_THRESHOLD, $ORIGINAL), so the verdict would not change; pick another TEST_URL"
echo "Score $SCORE is in [$NEW_THRESHOLD, $ORIGINAL): expect $before_verdict now and MALICIOUS after the change."

if ! $confirmed; then
    echo
    echo "Pre-flight passed. Nothing has been changed. Re-run with --yes to perform the demo."
    exit 0
fi

# ---------------------------------------------------------------- live demo
backup="$BACKUP_DIR/configmap-backup-$(date +%Y%m%d-%H%M%S).yaml"
mkdir -p "$BACKUP_DIR"
k get configmap "$CONFIGMAP" -o yaml > "$backup"
echo "Backed up the ConfigMap to $backup"

changed=false
restored=false
restore() {
    local rc=$?
    if $changed && ! $restored && [[ "$KEEP" != "1" ]]; then
        step "Restoring RISK_THRESHOLD=$ORIGINAL"
        k patch configmap "$CONFIGMAP" --type merge -p "{\"data\":{\"RISK_THRESHOLD\":\"$ORIGINAL\"}}" || true
        restart_and_wait || echo "Check manually: kubectl -n $NAMESPACE rollout status deployment/$DEPLOYMENT" >&2
        echo "scan-api pods now see RISK_THRESHOLD=$(current_threshold)"
    fi
    exit "$rc"
}
trap restore EXIT
trap 'exit 130' INT TERM

step "Changing RISK_THRESHOLD $ORIGINAL -> $NEW_THRESHOLD in the ConfigMap"
k patch configmap "$CONFIGMAP" --type merge -p "{\"data\":{\"RISK_THRESHOLD\":\"$NEW_THRESHOLD\"}}"
changed=true
k get configmap "$CONFIGMAP" -o jsonpath='{.data.RISK_THRESHOLD}'; echo
echo "Pods still see $(current_threshold) until they restart."

step "Restarting scan-api so the pods read the new value"
restart_and_wait
echo "scan-api pods now see RISK_THRESHOLD=$(current_threshold)"

step "After: same URL, same image, new threshold"
scan_model || die "no model-scored response after the change"
after_verdict="$VERDICT"
echo
echo "Verdict: $before_verdict (threshold $ORIGINAL) -> $after_verdict (threshold $NEW_THRESHOLD), score $SCORE"
[[ "$after_verdict" != "$before_verdict" ]] || echo "WARNING: the verdict did not change."

if [[ "$KEEP" == "1" ]]; then
    echo "KEEP=1: leaving RISK_THRESHOLD=$NEW_THRESHOLD. Restore with:"
    echo "  kubectl -n $NAMESPACE patch configmap $CONFIGMAP --type merge -p '{\"data\":{\"RISK_THRESHOLD\":\"$ORIGINAL\"}}'"
    echo "  kubectl -n $NAMESPACE rollout restart deployment/$DEPLOYMENT"
    restored=true
fi
# Normal exit: the EXIT trap restores the original threshold (unless KEEP=1).
