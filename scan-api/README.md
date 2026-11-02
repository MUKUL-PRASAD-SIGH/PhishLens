# scan-api

Public PhishLens API (Java 17, Spring Boot 3, Maven, port 8080). It scores a URL with `risk-model`, applies the verdict rules, falls back to local rules when the model is unavailable, and keeps the last 100 scans in memory. The contract is [docs/SPEC.md §3](../docs/SPEC.md).

## Build and run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # any JDK 17
mvn -B clean package                      # -> target/scan-api.jar
MODEL_URL=http://localhost:8000 $JAVA_HOME/bin/java -jar target/scan-api.jar
```

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/scan` | Body `{"url": "..."}`. Returns the SPEC §3.1 response. |
| GET | `/api/scans?limit=20` | JSON array of the same response shape, newest first. `limit` defaults to 20 and is clamped to 1..100 (an out-of-range value is not an error). |
| GET | `/api/info` | `{"service":"scan-api","version":...,"commit":...}` from `APP_VERSION` / `APP_COMMIT`; defaults are the Maven project version and `local`. |

## Probes and metrics

| Path | Use |
|---|---|
| `/actuator/health/liveness` | Liveness probe (`livenessState` only) |
| `/actuator/health/readiness` | Readiness probe (`readinessState` only). **Does not check `risk-model`**: a model outage is covered by fallback scoring and must not take pods out of service. |
| `/actuator/prometheus` | Prometheus scrape endpoint |

PhishLens metrics (SPEC §2), alongside the standard `http_server_requests_seconds_*` and JVM metrics:

| Prometheus name | Type | Meaning |
|---|---|---|
| `phishlens_scans_total{verdict}` | counter | Scans completed, per verdict (all three start at 0) |
| `phishlens_model_fallback_total` | counter | Scans scored by `FallbackScorer` because `risk-model` was unavailable |
| `phishlens_model_latency_seconds_{count,sum,max}{outcome}` | timer | Time spent calling `risk-model`; `outcome` is `success` or `error` (timeouts and failures included) |

## Behaviour on errors

| Situation | HTTP | Response |
|---|---|---|
| Valid URL, `risk-model` answers | 200 | `"source": "model"`, `modelVersion` from the model (e.g. `v2`) |
| `risk-model` times out, refuses the connection, returns any non-2xx (including `503` model not loaded) or an unusable body | **200** | Scored locally by `FallbackScorer`: `"source": "fallback"`, `"modelVersion": "fallback"` |
| `url` missing or blank | 400 | `{"status":400,"error":"Bad Request","message":"url must not be blank"}` |
| `url` not an http(s) URL with a valid host | 400 | `message`: `url must be an http or https URL with a valid host` |
| Body is not JSON | 400 | `message`: `request body must be JSON like {"url": "..."}` |
| `limit` is not a number | 400 | `message`: `limit must be a number` |

A model outage returns 200, not 5xx: the fallback exists so that a `risk-model` outage does not take the API down (SPEC §3.1). For the same reason readiness does not check `risk-model`.

### What counts as a valid URL

Same normalisation as `risk-model/app/features.py`: if the string has no `://`, `http://` is prepended. After that the URL must have an `http` or `https` scheme, contain no whitespace, and have a host that contains a dot or is an IPv4 literal. So `example.com/login` is accepted; `ftp://example.com`, `not a url` and `localhost` are rejected.

## Configuration

| Variable | Default | Used for |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `MODEL_URL` | `http://risk-model:8000` | `risk-model` base URL (`http://localhost:8000` locally) |
| `RISK_THRESHOLD` | `0.70` | Score at or above this is `MALICIOUS`. Read at startup, so a ConfigMap change takes effect on restart. |
| `MODEL_TIMEOUT_MS` | `800` | Total time to wait for `risk-model` before falling back (see below). Minimum 301. |
| `APP_VERSION` | project version | Shown by `/api/info` |
| `APP_COMMIT` | `local` | Shown by `/api/info` |

The `SUSPICIOUS` lower bound (`0.40`) is fixed in `application.yml` and is not an environment variable.

### Timeout budget

`MODEL_TIMEOUT_MS` is a single budget split in two, so the fallback starts after at most that long:

```
connect timeout = 300 ms
read timeout    = MODEL_TIMEOUT_MS - 300   (500 ms with the default 800)
worst case      = 300 + 500 = 800 ms, then fall back
```

**Minimum value: 301.** With `MODEL_TIMEOUT_MS` of 300 or less there is no read budget left, so scan-api refuses to start and logs `MODEL_TIMEOUT_MS must be at least 301 ...`. This is deliberate fail-fast: in Kubernetes a bad value shows up as `CrashLoopBackOff` rather than as silent fallback on every request.

## Fallback scoring

`FallbackScorer` is a port of `risk-model/app/scorer.py::score_v1` (the v1 weights in `V1_WEIGHTS`) using the features from `features.py`. If those weights or features change, update `FallbackScorer` and `UrlFeatureExtractor` to match.
