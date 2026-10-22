# PhishLens — Technical Spec

System requirements, metrics and the API/config contracts. **Contracts (§3) are frozen on day 1**: change them only through a PR that edits this file first and is approved by all three members.

---

## 1. System requirements

### 1.1 Whole stack on one machine (integration and final demo)

| | Minimum | Recommended |
|---|---|---|
| CPU | 4 cores, with virtualization (VT-x / AMD-V) enabled in BIOS | 6–8 cores |
| RAM | 8 GB (run the stages one at a time) | 16 GB (run everything together) |
| Free disk | 40 GB | 60 GB, SSD |
| OS | Windows 10/11 with WSL2, macOS 13+, or Ubuntu 22.04+ | Same |

Approximate footprint of each piece while it is running:

| Component | RAM | Disk | Notes |
|---|---|---|---|
| Docker Desktop / Engine | ~1.5–2 GB | ~10 GB of images | Use the WSL2 backend on Windows |
| Jenkins (container) | 1–2 GB | ~10 GB | Official floor is 256 MB RAM / 1 GB disk; 4 GB+ is recommended for small teams |
| Minikube | 4–6 GB allocated | 20 GB | Needs at least 2 CPUs, 2 GB free RAM and 20 GB disk; this project uses `--cpus=4` |
| Ansible target VM (Multipass) | 1–2 GB | ~10 GB | 1 vCPU is enough |
| IDE + browser | 2–3 GB | — | |
| **Total** | **~10–15 GB** | **~40–50 GB** | |

**On an 8 GB laptop:**

- Stop Jenkins and the VM before starting Minikube.
- Start Minikube with `--memory=4g`.
- Keep the manual scale demo at 5 replicas.

### 1.2 Per role

Until integration, each member only needs the tools for their own role.

| Member | Must install | Laptop |
|---|---|---|
| 1 — App & CI | Git, JDK 17, Maven 3.9+, Docker, Jenkins (Docker image) | 8 GB is fine |
| 2 — ML & containers | Git, Python 3.12, Docker | 8 GB is fine |
| 3 — Infra & deploy | Git, Docker, Minikube, kubectl, Ansible (Linux/WSL2), Multipass or VirtualBox | 16 GB preferred |

The integration and final demo run on whichever laptop has 16 GB, ideally Member 3's.

### 1.3 Software versions

| Tool | Version |
|---|---|
| Git | 2.40+ |
| JDK | 17 (Eclipse Temurin) |
| Maven | 3.9+ |
| Python | 3.12 |
| Docker | Engine 24+ or current Docker Desktop |
| Jenkins | `jenkins/jenkins:lts-jdk21` image, extended in `ci/jenkins/Dockerfile` with Maven, Python and the Docker CLI |
| Ansible | ansible-core 2.16+. The control node must be Linux, macOS or WSL2; native Windows is not supported. |
| Minikube + kubectl | Latest stable. kubectl must be within one minor version of the cluster, or use `minikube kubectl --`. |
| Optional | Trivy (image scan), `hey` (load test), Helm (only if adding Prometheus/Grafana) |

### 1.4 Cluster resource budget

| Workload | Replicas | CPU request / limit | Memory request / limit |
|---|---|---|---|
| `scan-api` | 3 (5 in the manual demo, HPA max 6) | 250m / 500m | 384Mi / 512Mi |
| `risk-model` | 2 | 100m / 250m | 128Mi / 256Mi |

- **CPU:** at 5 `scan-api` pods, requests total about 1.45 CPU. Kubernetes' own system pods use roughly another 0.75 CPU.
  - With `minikube start --cpus=2`, the 5th pod will stay `Pending`.
  - Use `--cpus=4`, or lower the `scan-api` CPU request to `150m`.
- **JVM memory:** set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` so the JVM sizes its heap to the container's 512Mi limit.

---

## 2. Metrics the system exposes

These metrics cover the four golden signals: latency, traffic, errors and saturation. Prometheus would scrape them; the report explains how.

**`scan-api`**, at `GET /actuator/prometheus` (Spring Boot Actuator + Micrometer):

| Metric | Type | What it tells you |
|---|---|---|
| `http_server_requests_seconds_count` / `_sum` / `_max` (by `uri`, `status`) | timer | Traffic, latency, error rate |
| `phishlens_scans_total{verdict}` | counter | Scans per verdict |
| `phishlens_model_fallback_total` | counter | How often `risk-model` was unreachable and fallback rules were used |
| `phishlens_model_latency_seconds` | timer | Time spent calling `risk-model` |
| `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage` | gauge / timer | JVM health and saturation |

**`risk-model`**, at `GET /metrics` (`prometheus-fastapi-instrumentator`):

| Metric | What it tells you |
|---|---|
| `http_requests_total`, `http_request_duration_seconds` | Request rate and latency |
| `phishlens_predictions_total{model_version}` | Predictions served per model version (useful during the v1 → v2 rollout) |

**Cluster**, via the metrics-server addon:

- `kubectl top pods -n phishlens` shows CPU and memory per pod.
- The HPA scales `scan-api` on average CPU utilization, with a 60% target.

---
## 3. Contracts


### 3.1 `scan-api` (port 8080)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/scan` | Score one URL |
| GET | `/api/scans?limit=20` | Recent scans, newest first (in memory, last 100 kept) |
| GET | `/api/info` | Service name, version and commit. Used to show which build is live during rollout and rollback. |
| GET | `/actuator/health/liveness`, `/actuator/health/readiness` | Kubernetes probes |
| GET | `/actuator/prometheus` | Metrics |

Request:

```json
POST /api/scan
{ "url": "http://192.168.10.5/secure-login/verify@account-update.xyz" }
```

Response (200):

```json
{
  "url": "http://192.168.10.5/secure-login/verify@account-update.xyz",
  "score": 0.91,
  "verdict": "MALICIOUS",
  "source": "model",
  "modelVersion": "v2",
  "scannedAt": "2026-10-22T09:00:00Z"
}
```

**Verdict rules:**

| Score | Verdict |
|---|---|
| below `0.40` | `SAFE` |
| `0.40` up to (not including) `RISK_THRESHOLD` | `SUSPICIOUS` |
| `RISK_THRESHOLD` or higher | `MALICIOUS` |

**Edge cases:**

- A missing or malformed URL returns `400`.
- If `risk-model` times out or is unreachable, `scan-api` scores the URL with its local fallback rules and returns `"source": "fallback"`.
- Readiness must not depend on `risk-model`. The fallback exists so that a model outage doesn't take the whole API down.

### 3.2 `risk-model` (port 8000)

| Method | Path | Purpose |
|---|---|---|
| POST | `/predict` | Score one URL |
| GET | `/health` | Liveness and readiness (`modelLoaded` must be true; returns `503` when the model is not loaded) |
| GET | `/metrics` | Metrics |

```json
POST /predict
{ "url": "http://192.168.10.5/secure-login/verify@account-update.xyz" }

200 OK
{
  "score": 0.91,
  "modelVersion": "v2",
  "features": {
    "length": 57, "num_digits": 7, "has_ip_host": true, "has_at_symbol": true,
    "num_subdomains": 0, "suspicious_tld": true, "uses_https": false, "entropy": 4.3
  }
}
```

### 3.3 Configuration

These values are environment variables. In Kubernetes they live in the ConfigMap `phishlens-config`.

| Variable | Service | Default | Notes |
|---|---|---|---|
| `SERVER_PORT` | scan-api | `8080` | |
| `MODEL_URL` | scan-api | `http://risk-model:8000` | `http://localhost:8000` when run locally |
| `RISK_THRESHOLD` | scan-api | `0.70` | |
| `MODEL_TIMEOUT_MS` | scan-api | `800` | After this, fall back to local rules |
| `APP_VERSION`, `APP_COMMIT` | scan-api | set at build | Shown by `/api/info` |
| `PORT` | risk-model | `8000` | |
| `MODEL_PATH` | risk-model | `/app/model/model.pkl` | |
| `MODEL_VERSION` | risk-model | `v1` or `v2` | |

### 3.4 Build outputs and image names

| Output | Name |
|---|---|
| JAR | `scan-api/target/scan-api.jar` (set `<finalName>scan-api</finalName>` in `pom.xml`) |
| Images | `phishlens/scan-api:<short-sha>`, `phishlens/risk-model:<short-sha>`, plus a `b<build-number>` tag |
| Rule | Never deploy `:latest`. Every running pod must trace back to a commit. |

Optional: Jenkins can push images to a free public Docker Hub repository so Member 3 can pull them on any machine. If you do this, replace `phishlens/` with your Docker Hub username.
