# PhishLens

**A malicious-URL risk scanner, built and shipped through a complete CI/CD → Kubernetes pipeline.**

DevOps project · Course 24CIAEC59 · Team of 3

> Status: `risk-model` (ML service) done; `scan-api`, CI, Ansible and Kubernetes pending. Team members: start with [docs/TASKS.md](docs/TASKS.md).

---

## What it does

PhishLens takes a URL and returns a phishing risk score and a verdict: `SAFE`, `SUSPICIOUS` or `MALICIOUS`. A browser extension, chat bot or mail filter could call it before a user opens a link.

The application is deliberately small: two services, about three endpoints each, and no database. The point of the project is the pipeline around it:

**code → test → build → containerize → configure → deploy → scale → recover**

| Service | Stack | Port | Responsibility |
|---|---|---|---|
| `scan-api` | Java 17, Spring Boot 3, Maven | 8080 | Public API, verdict rules, fallback rules, in-memory scan history |
| `risk-model` | Python 3.12, FastAPI, scikit-learn | 8000 | URL feature extraction and ML risk scoring |

The deployment demos use a version story: **v1** heuristic scoring, **v2** a trained ML model, **v3** a deliberately broken release that gets rolled back.

## Architecture

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

## Repository layout

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
├── docs/                        SPEC.md, TEAM_PLAN.md, report/ (drafts + screenshots)
├── repo_link.txt                [M1]
└── README.md
```

`[M1]`, `[M2]`, `[M3]` mark the owning team member.

## Quick start

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

## Documentation

| Doc | Contents |
|---|---|
| [docs/TASKS.md](docs/TASKS.md) | **Start here.** One-page task split per member, with checklists |
| [docs/SPEC.md](docs/SPEC.md) | System requirements, metrics, API and config contracts (frozen on day 1) |
| [docs/TEAM_PLAN.md](docs/TEAM_PLAN.md) | Roles, 21-day phase plan, handoffs, integration runbook, demo flow, evidence checklist, working rules, rubric map |

## Team

| Role | Name | GitHub |
|---|---|---|
| Member 1 — Application & CI | | |
| Member 2 — ML service & containers | | |
| Member 3 — Infrastructure & deployment | | |
