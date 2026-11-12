#!/usr/bin/env bash
# Controlled load test for the scan-api HPA.
#
# Usage:
#   TARGET_URL=http://127.0.0.1:54321 scripts/load-test.sh          # pre-flight checks and plan only
#   TARGET_URL=http://127.0.0.1:54321 scripts/load-test.sh --yes    # generate load and record the HPA
#
# TARGET_URL must reach the scan-api *Service*, so requests are spread over all pods. On Minikube
# with the Docker driver, get it from a second terminal with
#     minikube service scan-api -n phishlens --url
# and keep that terminal open. Do NOT use `kubectl port-forward svc/scan-api`: it pins every
# request to a single pod, so the average CPU the HPA sees rises by only a third.
#
# Load generator: `hey` (https://github.com/rakyll/hey) if it is on PATH, otherwise
# scripts/loadgen.py (Python 3 standard library only, nothing to install). LOADGEN=hey|python forces one.
#
# Settings (environment variables), with conservative defaults for a 2-CPU / 2-GiB Minikube:
#   REQUESTS      total requests                             default 2400
#   CONCURRENCY   concurrent workers                         default 4
#   QPS           rate limit per worker (0 = none)           default 10  (=> at most 40 req/s)
#   DURATION      if set (seconds, e.g. 120), run for this long instead of REQUESTS
#   ENDPOINT      scan | info                                default scan (POST /api/scan; info = GET /api/info)
#   SCAN_URL      URL sent in the scan body                  default http://shop.example.net/item?id=123
#   HPA_MAX_CAP   if set (e.g. 4), lower the HPA's maxReplicas to this for the test and restore the
#                 original value afterwards (limits how many pods a scale-up can add on a small node)
#   SAMPLE_EVERY  seconds between HPA/pod samples            default 15
#   SETTLE        seconds to keep sampling after the load    default 90
#   OUT_DIR       where the logs are written                 default evidence/k8s/load-test-<timestamp>
#
# Safety: the load stops automatically if a scan-api pod is Pending in two consecutive samples
# (the node has no room for it). Stop the Ansible VM and Jenkins before running this.
#
# The script reports a scale-up only if the replica count actually rose during the run.
set -euo pipefail

NAMESPACE="${NAMESPACE:-phishlens}"
DEPLOYMENT="scan-api"
HPA_NAME="scan-api-hpa"
REQUESTS="${REQUESTS:-2400}"
CONCURRENCY="${CONCURRENCY:-4}"
QPS="${QPS:-10}"
DURATION="${DURATION:-}"
ENDPOINT="${ENDPOINT:-scan}"
SCAN_URL="${SCAN_URL:-http://shop.example.net/item?id=123}"
HPA_MAX_CAP="${HPA_MAX_CAP:-}"
SAMPLE_EVERY="${SAMPLE_EVERY:-15}"
SETTLE="${SETTLE:-90}"
LOADGEN="${LOADGEN:-auto}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/../evidence/k8s/load-test-$(date +%Y%m%d-%H%M%S)}"

die() { echo "ERROR: $*" >&2; exit 1; }
k() { kubectl -n "$NAMESPACE" "$@"; }
replicas() { k get deployment "$DEPLOYMENT" -o jsonpath='{.status.replicas}'; }

confirmed=false
case "${1:-}" in
    --yes) confirmed=true ;;
    "") ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) die "unknown argument '$1' (use --yes to generate load)" ;;
esac

# ---------------------------------------------------------------- pre-flight (read-only)
command -v kubectl >/dev/null || die "kubectl not found on PATH"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace '$NAMESPACE' not found in context '$(kubectl config current-context 2>/dev/null)'. In WSL, kubectl may not point at the Windows Minikube; run this from Git Bash."
k get service "$DEPLOYMENT" >/dev/null 2>&1 || die "service '$DEPLOYMENT' not found"
k get deployment "$DEPLOYMENT" >/dev/null 2>&1 || die "deployment '$DEPLOYMENT' not found"
k get hpa "$HPA_NAME" >/dev/null 2>&1 || die "HPA '$HPA_NAME' not found; apply it first: kubectl apply -f k8s/hpa.yaml"
k top pods -l app=scan-api >/dev/null 2>&1 \
    || die "the metrics API is not answering (kubectl top pods failed). Enable it: minikube addons enable metrics-server"
[[ -z "$HPA_MAX_CAP" || "$HPA_MAX_CAP" =~ ^[0-9]+$ ]] || die "HPA_MAX_CAP must be a whole number"

# Pick the load generator.
python_cmd=()
# On Windows, resolve the real interpreter behind the `py` launcher, so stopping the generator's PID
# stops the generator itself rather than only the launcher.
if command -v py >/dev/null 2>&1 && py -3 -c 'import sys' >/dev/null 2>&1; then
    python_cmd=("$(py -3 -c 'import sys; print(sys.executable)' | tr -d '\r')")
elif command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; assert sys.version_info >= (3, 8)' >/dev/null 2>&1; then python_cmd=(python3)
fi
generator=""
case "$LOADGEN" in
    hey) command -v hey >/dev/null 2>&1 && generator=hey ;;
    python) [[ ${#python_cmd[@]} -gt 0 ]] && generator=python ;;
    auto) if command -v hey >/dev/null 2>&1; then generator=hey
          elif [[ ${#python_cmd[@]} -gt 0 ]]; then generator=python; fi ;;
    *) die "LOADGEN must be auto, hey or python" ;;
esac

target_ok=false
if [[ -n "${TARGET_URL:-}" ]]; then
    TARGET_URL="${TARGET_URL%/}"
    curl -s -m 5 -o /dev/null "$TARGET_URL/api/info" && target_ok=true
fi

case "$ENDPOINT" in
    scan) path=/api/scan; method=POST; body="{\"url\":\"$SCAN_URL\"}" ;;
    info) path=/api/info; method=GET; body="" ;;
    *) die "ENDPOINT must be 'scan' or 'info'" ;;
esac
url="${TARGET_URL:-<TARGET_URL>}$path"

if [[ "$generator" == hey ]]; then
    gen_cmd=(hey -c "$CONCURRENCY")
    if [[ -n "$DURATION" ]]; then gen_cmd+=(-z "${DURATION}s"); else gen_cmd+=(-n "$REQUESTS"); fi
    [[ "$QPS" != "0" ]] && gen_cmd+=(-q "$QPS")
    [[ -n "$body" ]] && gen_cmd+=(-m "$method" -T application/json -d "$body")
    gen_cmd+=("$url")
else
    gen_cmd=("${python_cmd[@]}" "$SCRIPT_DIR/loadgen.py" --url "$url" -m "$method" -c "$CONCURRENCY" -q "$QPS")
    if [[ -n "$DURATION" ]]; then gen_cmd+=(-z "$DURATION"); else gen_cmd+=(-n "$REQUESTS"); fi
    [[ -n "$body" ]] && gen_cmd+=(-d "$body")
fi

echo "HPA before the test:"
k get hpa "$HPA_NAME"
echo
k top pods -l app=scan-api
echo
echo "Load generator:     ${generator:-none found}"
echo "Load command:       ${gen_cmd[*]}"
echo "TARGET_URL:         ${TARGET_URL:-<not set>} (reachable: $target_ok)"
[[ -n "$HPA_MAX_CAP" ]] && echo "HPA maxReplicas:    temporarily capped at $HPA_MAX_CAP, restored afterwards"

if [[ -z "$generator" ]]; then
    cat <<'EOF'

No load generator found. This script does not install anything. Either:
  - install hey: Windows: download hey_windows_amd64 from https://github.com/rakyll/hey/releases,
    rename it hey.exe and put it on PATH (or `go install github.com/rakyll/hey@latest`);
    Ubuntu/WSL: sudo apt install hey
  - or install Python 3.8+ (scripts/loadgen.py needs only the standard library)
EOF
fi
if [[ -z "${TARGET_URL:-}" ]]; then
    echo
    echo "Set TARGET_URL to the URL printed by: minikube service scan-api -n $NAMESPACE --url"
fi
if ! $confirmed || [[ -z "$generator" ]] || ! $target_ok; then
    echo
    echo "Nothing has been changed. Re-run with --yes once a generator is available and TARGET_URL is reachable."
    $confirmed && exit 1
    exit 0
fi

# ---------------------------------------------------------------- load test
mkdir -p "$OUT_DIR"
SAMPLES="$OUT_DIR/hpa-samples.log"
STOP_FLAG="$OUT_DIR/.stop"
echo "Writing logs to $OUT_DIR"

original_max=$(k get hpa "$HPA_NAME" -o jsonpath='{.spec.maxReplicas}')
sampler_pid=""; tail_pid=""; gen_pid=""
# The HPA remembers its recommendations for the scale-down stabilization window (300 s by default),
# including the uncapped ones computed while maxReplicas was lowered. Raising maxReplicas inside
# that window makes it jump straight to the remembered value (seen on 2026-11-05: 4 -> 6 at 3% CPU,
# which the 2-GiB node could not hold). So the cap is lifted only once the window has passed since
# the load ended; on an early exit the cap is left in place with instructions.
STABILIZATION_WAIT="${STABILIZATION_WAIT:-330}"
load_end=""
cleanup() {
    local rc=$?
    for pid in "$gen_pid" "$sampler_pid" "$tail_pid"; do [[ -n "$pid" ]] && kill "$pid" 2>/dev/null; done
    if [[ -n "$HPA_MAX_CAP" ]]; then
        if [[ -n "$load_end" ]] && (( SECONDS - load_end >= STABILIZATION_WAIT )); then
            k patch hpa "$HPA_NAME" --type merge -p "{\"spec\":{\"maxReplicas\":$original_max}}" >/dev/null \
                && echo "HPA maxReplicas restored to $original_max" \
                || echo "WARNING: restore the HPA with: kubectl apply -f k8s/hpa.yaml" >&2
        else
            echo "WARNING: the HPA is left capped at maxReplicas=$HPA_MAX_CAP because its 5-minute" >&2
            echo "stabilization window has not passed. Wait ${STABILIZATION_WAIT}s after the load stopped, then run:" >&2
            echo "  kubectl apply -f k8s/hpa.yaml" >&2
        fi
    fi
    rm -f "$STOP_FLAG"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ -n "$HPA_MAX_CAP" ]]; then
    k patch hpa "$HPA_NAME" --type merge -p "{\"spec\":{\"maxReplicas\":$HPA_MAX_CAP}}"
fi

start_replicas=$(replicas)
{
    echo "# load test started $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "# generator: ${gen_cmd[*]}"
    echo "# starting replicas: $start_replicas; HPA maxReplicas during the test: $(k get hpa "$HPA_NAME" -o jsonpath='{.spec.maxReplicas}') (manifest value $original_max)"
    k get hpa "$HPA_NAME"
    k top nodes
    k top pods -l app=scan-api
} > "$OUT_DIR/before.txt" 2>&1

sample() {
    local pending
    echo "--- $(date '+%H:%M:%S') replicas=$(replicas || echo '?')"
    k get hpa "$HPA_NAME" --no-headers || echo "(hpa not readable this sample)"
    k top pods -l app=scan-api --no-headers 2>/dev/null || echo "(metrics not available this sample)"
    pending=$(k get pods -l app=scan-api --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l)
    if (( pending > 0 )); then
        echo "WARNING: $pending scan-api pod(s) Pending"
        if [[ -f "$STOP_FLAG.pending" ]]; then echo "ABORT: pods Pending twice in a row, stopping the load"; touch "$STOP_FLAG"; fi
        touch "$STOP_FLAG.pending"
    else
        rm -f "$STOP_FLAG.pending"
    fi
}

( while true; do sample >> "$SAMPLES" 2>&1; sleep "$SAMPLE_EVERY"; done ) &
sampler_pid=$!
touch "$SAMPLES"
tail -f "$SAMPLES" &
tail_pid=$!

echo "Generating load (Ctrl-C stops it; the HPA cap is lifted only after its stabilization window)..."
"${gen_cmd[@]}" > "$OUT_DIR/generator-output.txt" 2>&1 &
gen_pid=$!
while kill -0 "$gen_pid" 2>/dev/null; do
    if [[ -f "$STOP_FLAG" ]]; then kill "$gen_pid" 2>/dev/null; echo "Load stopped by the Pending-pod guard."; break; fi
    sleep 5
done
wait "$gen_pid" 2>/dev/null || true
gen_pid=""
load_end=$SECONDS

echo
echo "Load finished at $(date '+%H:%M:%S'). Sampling for another ${SETTLE}s..."
sleep "$SETTLE"
if [[ -n "$HPA_MAX_CAP" ]] && (( SECONDS - load_end < STABILIZATION_WAIT )); then
    echo "Keeping the HPA cap until its stabilization window has passed ($((STABILIZATION_WAIT - (SECONDS - load_end)))s more)..."
    sleep $((STABILIZATION_WAIT - (SECONDS - load_end)))
fi
kill "$sampler_pid" "$tail_pid" 2>/dev/null || true
sampler_pid=""; tail_pid=""
rm -f "$STOP_FLAG.pending"

end_replicas=$(replicas)
max_replicas=$(grep -o 'replicas=[0-9]*' "$SAMPLES" | cut -d= -f2 | sort -n | tail -1)
max_replicas="${max_replicas:-$start_replicas}"
{
    echo "# finished $(date '+%Y-%m-%d %H:%M:%S %z')"
    k get hpa "$HPA_NAME"
    k describe hpa "$HPA_NAME"
    k top pods -l app=scan-api
    k get pods -l app=scan-api -o wide
} > "$OUT_DIR/after.txt" 2>&1

echo
echo "Generator summary:"
cat "$OUT_DIR/generator-output.txt"
echo
echo "Replicas: start $start_replicas, highest sampled $max_replicas, end $end_replicas"
if (( max_replicas > start_replicas )); then
    echo "RESULT: the HPA scaled scan-api up from $start_replicas to $max_replicas during this test."
else
    echo "RESULT: no scale-up observed; replicas stayed at $start_replicas. Do not report HPA scaling from this run."
fi
echo "Logs: $OUT_DIR (before.txt, hpa-samples.log, generator-output.txt, after.txt)"
