# PhishLens

**A malicious-URL risk scanner, built and shipped through a complete CI/CD → Kubernetes pipeline.**

DevOps project · Course 24CIAEC59 · Team of 3

> Status: planning. Tick items off in [Team roles](#7-team-roles) and [Milestones](#9-milestones) as they land.

---

## 1. What it does

PhishLens takes a URL and returns a phishing risk score and a verdict: `SAFE`, `SUSPICIOUS` or `MALICIOUS`. A browser extension, chat bot or mail filter could call it before a user opens a link.

The application is deliberately small: two services, about three endpoints each, and no database. The point of the project is the pipeline around it:

**code → test → build → containerize → configure → deploy → scale → recover**

| Service | Stack | Port | Responsibility |
|---|---|---|---|
| `scan-api` | Java 17, Spring Boot 3, Maven | 8080 | Public API, verdict rules, fallback rules, in-memory scan history |
| `risk-model` | Python 3.12, FastAPI, scikit-learn | 8000 | URL feature extraction and ML risk scoring |

The service also has a version story that the deployment demos use:

- **v1:** heuristic scoring
- **v2:** a trained ML model
- **v3:** a deliberately broken release that gets rolled back

---

## 2. Architecture

```
Component 1 — Continuous Integration
  git push ─▶ GitHub ─(SCM poll)─▶ Jenkins: test ─▶ package ─▶ docker build ─▶ scan
                                        │
                                        ├─▶ scan-api.jar              (archived artifact)
                                        └─▶ phishlens/*:<git-sha>     (versioned images)

Component 2 — Configure, deploy, scale
  scan-api.jar ─▶ Ansible role ─▶ Ubuntu VM  (runs as a systemd service)

  images ─▶ Minikube, namespace "phishlens"
            client ─▶ NodePort ─▶ scan-api   (3 → 5 pods, HPA up to 6)
                                     │  POST /predict
                                     ▼
                       ClusterIP ─▶ risk-model (2 pods)
            ConfigMap · liveness/readiness probes · rollout undo
```

---

## 3. System requirements

### 3.1 Whole stack on one machine (integration and final demo)

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

### 3.2 Per role

Until integration, each member only needs the tools for their own role.

| Member | Must install | Laptop |
|---|---|---|
| 1 — App & CI | Git, JDK 17, Maven 3.9+, Docker, Jenkins (Docker image) | 8 GB is fine |
| 2 — ML & containers | Git, Python 3.12, Docker | 8 GB is fine |
| 3 — Infra & deploy | Git, Docker, Minikube, kubectl, Ansible (Linux/WSL2), Multipass or VirtualBox | 16 GB preferred |

The integration and final demo run on whichever laptop has 16 GB, ideally Member 3's.

### 3.3 Software versions

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

### 3.4 Cluster resource budget

| Workload | Replicas | CPU request / limit | Memory request / limit |
|---|---|---|---|
| `scan-api` | 3 (5 in the manual demo, HPA max 6) | 250m / 500m | 384Mi / 512Mi |
| `risk-model` | 2 | 100m / 250m | 128Mi / 256Mi |

- **CPU:** at 5 `scan-api` pods, requests total about 1.45 CPU. Kubernetes' own system pods use roughly another 0.75 CPU.
  - With `minikube start --cpus=2`, the 5th pod will stay `Pending`.
  - Use `--cpus=4`, or lower the `scan-api` CPU request to `150m`.
- **JVM memory:** set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` so the JVM sizes its heap to the container's 512Mi limit.

---

## 4. Metrics the system exposes

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

## 5. Contracts (frozen on day 1)

This section is what lets three people work in parallel without waiting on each other. Change it only through a PR that edits this README first and is approved by all three members.

### 5.1 `scan-api` (port 8080)

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

### 5.2 `risk-model` (port 8000)

| Method | Path | Purpose |
|---|---|---|
| POST | `/predict` | Score one URL |
| GET | `/health` | Liveness and readiness (`modelLoaded` must be true) |
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

### 5.3 Configuration

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

### 5.4 Build outputs and image names

| Output | Name |
|---|---|
| JAR | `scan-api/target/scan-api.jar` (set `<finalName>scan-api</finalName>` in `pom.xml`) |
| Images | `phishlens/scan-api:<short-sha>`, `phishlens/risk-model:<short-sha>`, plus a `b<build-number>` tag |
| Rule | Never deploy `:latest`. Every running pod must trace back to a commit. |

Optional: Jenkins can push images to a free public Docker Hub repository so Member 3 can pull them on any machine. If you do this, replace `phishlens/` with your Docker Hub username.

---

## 6. Repository layout

```
phishlens/
├── scan-api/                    [M1] Spring Boot service
│   ├── pom.xml
│   ├── src/main/java/...        controller, verdict rules, model client, fallback rules
│   ├── src/test/java/...        JUnit + MockMvc tests
│   └── Dockerfile               [M2] multi-stage build
├── risk-model/                  [M2] FastAPI + scikit-learn service
│   ├── app/                     main.py, features.py, scorer.py
│   ├── model/                   model.pkl, MODEL_CARD.md
│   ├── tests/                   pytest
│   ├── train.py
│   ├── requirements.txt
│   └── Dockerfile
├── ci/jenkins/Dockerfile        [M1] Jenkins image with Maven, Python, Docker CLI
├── Jenkinsfile                  [M1]
├── .github/
│   ├── workflows/ci.yml         [M1] optional GitHub Actions
│   └── CODEOWNERS               folder → owner mapping (auto-requests reviews)
├── ansible/                     [M3]
│   ├── inventory.ini
│   ├── playbook.yml
│   └── roles/scan_api/          defaults, tasks, handlers, templates
├── k8s/                         [M3]
│   ├── 00-namespace.yaml
│   ├── 01-configmap.yaml
│   ├── 10-risk-model.yaml       Deployment + ClusterIP Service
│   ├── 20-scan-api.yaml         Deployment + NodePort Service
│   └── 30-hpa.yaml
├── scripts/                     [M3] demo-scale.sh, demo-rollback.sh, load-test.sh
├── docker-compose.yml           [M2] both services locally, without Kubernetes
├── docs/report/                 [all] report drafts and screenshots
├── repo_link.txt                [M1]
└── README.md
```

---

## 7. Team roles

Work is split by pipeline stage, so each person owns separate folders and a separate share of the marks. With the contracts in §5 and the day-1 skeletons, nobody blocks anybody.

### Member 1 — Application & CI lead · `[Name]`

**Owns:** `scan-api/` (code and tests), `Jenkinsfile`, `ci/jenkins/`, `.github/workflows/`, repo admin
**Rubric area:** Component 1 — Git, Maven, Jenkins, CI enhancement

- [ ] Create the repo, protect `main` (PRs required), add `CODEOWNERS`, add the course coordinator as a collaborator, fill in `repo_link.txt`
- [ ] **Day-1 skeleton:** `/api/scan` returns a fixed `SAFE` verdict, so the others have a real JAR to containerize and deploy
- [ ] Implement the endpoints, verdict rules, in-memory history, `RiskModelClient` with timeout, and fallback rules
- [ ] JUnit tests: threshold boundaries, controller (MockMvc), and fallback when the model is down (`MockRestServiceServer`)
- [ ] `pom.xml` with `web`, `validation`, `actuator`, `micrometer-registry-prometheus` and `spring-boot-maven-plugin`
- [ ] Run Jenkins in Docker. Create a Pipeline job from SCM with polling (`* * * * *`) and these stages:
  1. Checkout
  2. Test (`mvn test` and `pytest`)
  3. Package
  4. Archive (JAR + JUnit XML)
  5. Docker build
  6. Image scan
- [ ] **Graded enhancement:** the automated test stage, plus the JAR and images tagged with the commit SHA
- [ ] Bonus: a Trivy stage that fails on `CRITICAL` vulnerabilities, and a GitHub Actions workflow

**Works independently because:**

- `risk-model` is mocked in unit tests.
- The fallback rules let `scan-api` run on its own.
- The Docker stages are guarded with `when { expression { fileExists('scan-api/Dockerfile') } }`, so they switch on automatically once Member 2's Dockerfiles merge.

**Report:** Component 1 Implementation, Enhancement and Results, including the Git and Jenkins screenshots.

### Member 2 — ML service & containers lead · `[Name]`

**Owns:** `risk-model/`, both Dockerfiles, `docker-compose.yml`
**Rubric area:** Component 2 Docker step, the second-microservice and multi-stage enhancements, and the metrics explanation. Also supplies the pytest suite used in Component 1's test stage.

- [ ] **Day-1 skeleton:** `/predict` returns a fixed `0.5` and `/health` returns ok
- [ ] `features.py`: length, digit count, special characters, `@`, IP host, subdomain count, suspicious TLD, HTTPS, entropy
- [ ] **v1:** a weighted heuristic score (`MODEL_VERSION=v1`)
- [ ] **v2:** `train.py` on a public phishing-URL dataset (for example Kaggle or PhishTank) → logistic regression or random forest → `model.pkl`
  - Write `MODEL_CARD.md` with the dataset source, license, accuracy, precision and recall.
  - Don't commit the raw dataset.
- [ ] pytest:
  - feature cases, e.g. an IP host is flagged and a long URL scores higher
  - API tests with `TestClient`
- [ ] `/metrics` with `prometheus-fastapi-instrumentator`
- [ ] Dockerfiles, both running as non-root with a `.dockerignore`:
  - `scan-api`: multi-stage, `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre`
  - `risk-model`: `python:3.12-slim`
- [ ] Record image sizes for single-stage vs multi-stage builds, for the report
- [ ] `docker-compose.yml` that runs both services together for local end-to-end checks

**Works independently because:** this role needs only Python and Docker, and the `scan-api` Dockerfile can be built against Member 1's skeleton source.

**Report:** the Docker section, the second-microservice and multi-stage enhancements, and the explanation of what Prometheus would scrape.

### Member 3 — Infrastructure & deployment lead · `[Name]`

**Owns:** `ansible/`, `k8s/`, `scripts/`
**Rubric area:** Component 2 — Ansible, Kubernetes deployment, scaling, deployment enhancements (probes, ConfigMap, rollback, HPA)

- [ ] Ansible target: a Multipass Ubuntu 22.04 VM, `inventory.ini`, and SSH key access
  - Use a real VM: systemd does not run in a plain Docker container.
- [ ] Create the role with `ansible-galaxy init roles/scan_api`. Defaults: `app_port`, `app_user`, `jar_src`, `model_url`
- [ ] Role tasks:
  - install `openjdk-17-jre-headless`
  - create the service user
  - copy the JAR to `/opt/phishlens`
  - fill in the systemd unit from a template
  - enable and start the service
- [ ] Handler `restart phishlens`, notified only when the JAR or unit file changes. Run the playbook twice to show the second run makes no changes.
- [ ] Kubernetes manifests: namespace, ConfigMap, Deployments with matching labels and selectors, Services, probes, and resource requests and limits
- [ ] Scaling demo: scale 3 → 5 → 2 manually. Then enable metrics-server, add an HPA (min 3, max 6, 60% CPU), and load-test with `hey`.
- [ ] Rollback demo:
  1. Deploy v3 with a failing readiness check.
  2. The rollout stalls while the old pods keep serving traffic.
  3. Run `kubectl rollout undo`.
  4. Show `kubectl rollout history`.
- [ ] Scripts in `scripts/` so each demo can be replayed with one command

**Works independently because:**

- The Ansible role is built and tested against Member 1's skeleton JAR.
- The manifests are developed with a stand-in image (for example `nginx`, with probes on `/`) and switched to the real images at integration.

**Report:** the Ansible, Kubernetes, scaling and deployment-enhancement sections.

### Shared by all three

- **Report sections:** each member writes one paragraph each for the Abstract, Introduction, Conclusion and References.
  - Member 1 compiles the Component 1 report.
  - Member 3 compiles the Component 2 report.
- **Reviews:** every PR is reviewed by one other member. That review history also counts as evidence for the Git marks.
- **Screenshots:** take them as each task finishes, not at the end. Save them in `docs/report/screenshots/<component>/`.

---

## 8. Working rules

- **Branches:** `feat/<area>-<short-desc>`, e.g. `feat/api-scan-endpoint`, `feat/model-train`, `feat/k8s-hpa`. Nobody pushes directly to `main`.
- **Commits:** follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`, `ci:`, `docs:`.
- **Contract changes (§5):** the PR edits this README first and needs all three approvals before any code changes.
- **Ownership:** stay inside your own folders. To change someone else's, open a PR or issue; `CODEOWNERS` tags the owner automatically.
- **Updates:** post one line in the group chat whenever you merge something.

---

## 9. Milestones

- [ ] **M0 — Setup (days 1–2).**
  - Repo created and the contract in §5 agreed.
  - Both service skeletons merged into `main`.
  - This unblocks everyone.
- [ ] **M1 — Independent build.**
  - M1: Jenkins goes green on every push, with tests.
  - M2: v1 service, Dockerfiles and compose file working.
  - M3: Ansible role working against the skeleton JAR, and manifests applied with stand-in images.
- [ ] **M2 — Integration (together, on the 16 GB machine).**
  - Build the real images (or let Jenkins build them) and load them into Minikube.
  - Run `kubectl apply`.
  - Check end to end with `curl` through the NodePort.
- [ ] **M3 — Enhancements.**
  - SHA tags and the Trivy scan.
  - v1 → v2 model rollout.
  - Probes, ConfigMap and HPA.
  - v3 rollback demo.
- [ ] **M4 — Wrap-up.**
  - Screenshot checklist complete.
  - Both component reports compiled.
  - Full demo rehearsed twice.

---

## 10. Quick start

```bash
# 1. Run the services locally
cd risk-model && pip install -r requirements.txt && uvicorn app.main:app --port 8000
cd scan-api && mvn clean package && java -jar target/scan-api.jar
# ...or both at once
docker compose up --build

curl -s -X POST localhost:8080/api/scan \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://192.168.10.5/secure-login/verify@account-update.xyz"}'

# 2. Configure the VM with Ansible
cd ansible && ansible-playbook -i inventory.ini playbook.yml

# 3. Deploy on Kubernetes
minikube start --cpus=4 --memory=6g
minikube addons enable metrics-server
SHA=$(git rev-parse --short HEAD)
minikube image load phishlens/scan-api:$SHA
minikube image load phishlens/risk-model:$SHA
kubectl apply -f k8s/
kubectl -n phishlens set image deploy/scan-api scan-api=phishlens/scan-api:$SHA
kubectl -n phishlens set image deploy/risk-model risk-model=phishlens/risk-model:$SHA
kubectl -n phishlens get pods,svc
minikube service scan-api -n phishlens --url   # on Docker Desktop this keeps a tunnel open; use a second terminal

# 4. Scale and roll back
kubectl -n phishlens scale deploy/scan-api --replicas=5
kubectl -n phishlens get pods -w
kubectl -n phishlens rollout history deploy/scan-api
kubectl -n phishlens rollout undo deploy/scan-api
```

To get readable rollout history, annotate each release with a change cause:

```bash
kubectl -n phishlens annotate deploy/scan-api kubernetes.io/change-cause="v2: trained model"
```

---

## 11. Rubric map

| Requirement | Where | Owner |
|---|---|---|
| REST app with 2–3 endpoints that responds to curl | `scan-api/`, `risk-model/` | M1, M2 |
| At least 2 branches and a merge | Git history and PRs | All |
| Maven build | `scan-api/pom.xml` | M1 |
| Jenkins: SCM, trigger, build step, archived artifact | `Jenkinsfile` | M1 |
| CI enhancement | Test stage + commit-SHA versioning | M1 (tests from M1 and M2) |
| Ansible: inventory, variable, handler, role | `ansible/` | M3 |
| Dockerfile | `*/Dockerfile` | M2 |
| Deployment (3 replicas) + Service | `k8s/` | M3 |
| Scaling up and down | `scripts/demo-scale.sh` | M3 |
| Deployment enhancement | Second microservice and multi-stage (M2); probes, ConfigMap, rollback (M3) | M2, M3 |
| Repo link and coordinator access | `repo_link.txt`, GitHub settings | M1 |

---

## 12. Resume lines

> **PhishLens — Malicious URL Risk Scanner: CI/CD & Kubernetes Deployment** | [GitHub link]
> Java 17 / Spring Boot, Python / FastAPI, scikit-learn, Git, Maven, Jenkins, Docker, Ansible, Kubernetes
> • Built a two-service URL risk-scoring API (Spring Boot gateway + ML model service) with a Jenkins pipeline triggered on every Git push, gated by JUnit/pytest tests and tagging every image with its commit SHA.
> • Deployed on Kubernetes with ConfigMap-driven config, liveness/readiness probes and HPA autoscaling; provisioned hosts with an Ansible role and demonstrated rollback of a faulty release.

---

## Team

| Role | Name | GitHub |
|---|---|---|
| Member 1 — Application & CI | | |
| Member 2 — ML service & containers | | |
| Member 3 — Infrastructure & deployment | | |
