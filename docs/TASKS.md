# PhishLens — Who Does What

Find your name, read only your section, tick the boxes as you go. Details and reasons live in [TEAM_PLAN.md](TEAM_PLAN.md); API and config rules live in [SPEC.md](SPEC.md).

**Progress (2026-11-05):** All PRs (#11–#19) are merged; `main` holds every branch except `demo/v3-broken` (kept unmerged on purpose). Done: `scan-api`, Jenkins, GitHub Actions, Docker build/push and Trivy stages (Docker Hub screenshots for both images are in), both Dockerfiles, `risk-model` v1/v2, and the first K8s manifests and `demo-scale` scripts. Still open: `docker-compose.yml`, the v3 image, the Ansible role, K8s namespace/HPA manifests, the rest of M3's scripts and demos, and the reports. `[x]` = done, `[~]` = written but not yet verified.

## The split at a glance

| | **M1 — Builder** | **M2 — Packager** | **M3 — Deployer** |
|---|---|---|---|
| **Name** | | | |
| **Job in one line** | Write the Java API and make Jenkins build it on every push | Write the Python model and put both services in Docker | Install it on a VM with Ansible and run it on Kubernetes |
| **Your folders** | `scan-api/`, `Jenkinsfile`, `ci/`, `.github/` | `risk-model/`, `docker-compose.yml`, both `Dockerfile`s | `ansible/`, `k8s/`, `scripts/` |
| **Your report part** | Component 1 (Git, Maven, Jenkins) | Docker, model, metrics | Component 2 (Ansible, Kubernetes) |
| **Reviews PRs from** | M3 | M1 | M2 |
| **Laptop** | 8 GB OK | 8 GB OK | 16 GB (hosts integration and the final demo) |

## Rules for everyone

1. Work only in **your own folders**. Need a change elsewhere? Open a GitHub issue for the owner.
2. Never push to `main`. Use a branch named `feat/<area>-<what>`, open a PR, and get **one review** from the person in the table above.
3. Commit messages start with `feat:`, `fix:`, `test:`, `ci:` or `docs:`.
4. Don't change the API or config rules in `SPEC.md` §3 without all three agreeing in a PR.
5. Every evening post one line in the group chat: **done / next / blocked**. Stuck for 2+ hours? Post the error and the command you ran.
6. Take screenshots **when you finish a task**, not at the end. Save to `docs/report/screenshots/`.

## Who waits on whom

```
Day 2   M1 scan-api skeleton ──▶ M2, M3        M2 risk-model skeleton ──▶ M1
Day 7   M2 Dockerfiles + tests + compose ──▶ M1, M3
Day 14  M2 v2 image tag ──▶ M3                 M1 v3-broken image tag ──▶ M3
```

If something is late, use the workaround in the TEAM_PLAN handoffs table and keep going.

---

## M1 — Builder (Java API + Jenkins)

**Days 1–2: setup (everyone is waiting on you)**
- [~] Create the GitHub repo, protect `main` (PRs required, 1 approval), invite M2, M3 and the course coordinator *(Repo and PR workflow are in place; branch protection could not be confirmed, and the course coordinator is not yet a collaborator.)*
- [~] Put your teammates' GitHub usernames into `.github/CODEOWNERS` and the repo URL into `repo_link.txt` *(Repo URL, M1 and M2 are in; M3's username is in `CODEOWNERS` via PR #11.)*
- [x] Create the Spring Boot project in `scan-api/` (Java 17, Maven, Web, Validation, Actuator), `finalName` = `scan-api`
- [x] `POST /api/scan` returns a fixed `SAFE`, plus `GET /api/info`. **Merge this: M2 and M3 need it.**

**Days 3–8: the real API and Jenkins**
- [x] Verdict rules, scan history (last 100), model client with timeout, fallback scoring (use M2's v1 weights, Day 5)
- [x] Metrics and health probes in `application.yml`; tell M3 the probe paths (Day 6) *(M3's manifests use these paths.)*
- [x] JUnit tests: thresholds 0.39 / 0.40 / 0.69 / 0.70, controller 200 and 400, fallback when model is down *(95 tests.)*
- [x] Jenkins in Docker (`ci/jenkins/Dockerfile`)
- [x] `Jenkinsfile`: Checkout → Test → Package → Archive, polling every minute; run it once by hand, then push and watch it start itself *(Build #2 started itself from a push; the job now builds `main`.)*
- [x] Add a Python test stage for M2's pytest suite (Day 7) *(13 pytest tests; 108 with the JUnit tests.)*
- [x] Tag the JAR and build name with the commit SHA

**Days 9–11: integration (on M3's laptop)**
- [x] Add Docker build and push stages (Docker Hub); agree image names and tags with M3 by Day 8 *(Stages are on `main`; Docker Hub shows both `scan-api` and `risk-model`, screenshots 29–30.)*
- [x] Push a commit and show Jenkins building and pushing the images *(Screenshots 27–28, 33.)*

**Days 12–16: extras**
- [x] Trivy scan stage; optional GitHub Actions workflow *(Workflow green on `main`; Trivy ran, with before/after shots 26 and 34 and CVE fixes in #16.)*
- [~] Build the deliberately broken **v3** image on branch `demo/v3-broken` (never merge it) and give M3 the tag by Day 14 *(The branch is rebased onto `main` with 96 tests passing; the image can now be built from `scan-api/Dockerfile` but has not been yet.)*

**Days 17–21: wrap-up**
- [~] Screenshots: git graph with a merge, Jenkins config, auto-triggered build, SHA-named artifact, test results *(All now in `docs/report/screenshots/component1/` (shots 14–34); only the report write-up remains.)*
- [ ] Write and compile the Component 1 report; rehearse your 4-minute demo part

---

## M2 — Packager (Python model + Docker)

**Days 1–2: setup**
- [x] Create `risk-model/` with FastAPI: `POST /predict` returns a fixed `0.5`, `GET /health` returns ok. **Merge this: M1 needs it.**
- [ ] Pick a public phishing-URL dataset (Kaggle or PhishTank), note its license, start the download *(Using synthetic data for now; see `risk-model/model/MODEL_CARD.md`. Swap in a real dataset with `train.py --data`.)*

**Days 3–8: the model and containers (your Day 7 handoff unblocks both teammates)**
- [x] `features.py`: URL length, digits, special characters, `@`, IP host, subdomains, suspicious TLD, HTTPS, entropy
- [x] v1 heuristic score (weighted sum, 0–1). **Send the weights to M1 by Day 5.** *(Weights: `V1_WEIGHTS` in `risk-model/app/scorer.py`. scan-api `FallbackScorer` already uses the same weights, so this handoff is done.)*
- [x] pytest suite: feature tests and API tests (200, 422, `/health`)
- [~] `risk-model/Dockerfile` (`python:3.12-slim`, non-root) and `.dockerignore` *(Written, image build not yet verified; Docker was not running.)*
- [~] `scan-api/Dockerfile` as a **multi-stage** build (Maven build stage, JRE runtime stage, non-root); also build a single-stage one and note both image sizes *(Both Dockerfiles are on `main`; image sizes not yet recorded. `docker-compose.yml` is still missing.)*
- [ ] `docker-compose.yml`: `docker compose up --build`, then curl on port 8080 must show `source: model`
- [x] `/metrics` endpoint and the `phishlens_predictions_total` counter

**Days 9–11: integration**
- [ ] On M3's laptop, run compose first and prove the images work before Kubernetes

**Days 12–16: extras**
- [x] `train.py` → `model.pkl` (v2), pin the scikit-learn version, write `MODEL_CARD.md` with v1 vs v2 accuracy, precision, recall. *(v2 trained on synthetic data.)* **Give M3 the v2 image tag by Day 14.**
- [ ] Update base images if Trivy flags fixable CRITICAL issues
- [x] Write the Prometheus explanation (sample scrape config and what each metric means) *(See `docs/PROMETHEUS.md`.)*

**Days 17–21: wrap-up**
- [ ] Screenshots: Dockerfiles and build, `docker images` size comparison, compose curl, model card, metrics output
- [ ] Write your report sections; rehearse your 3-minute demo part

---

## M3 — Deployer (Ansible + Kubernetes)

You wait on others the most early on, so use stand-ins (any Spring Boot JAR, an `nginx` image) until the real pieces arrive.

**Days 1–2: setup**
- [ ] Ubuntu VM (Multipass), SSH key, `ansible/inventory.ini`; `ansible all -m ping` returns pong
- [ ] `minikube start --cpus=4 --memory=6g`; apply the namespace and a stand-in nginx Deployment

**Days 3–8: Ansible and manifests**
- [ ] Ansible role `scan_api`: variables, install Java, create user, copy JAR, systemd template, handler `restart phishlens`
- [ ] Run the playbook twice: first run changes things, **second run shows `changed=0`**
- [~] K8s manifests in `k8s/`: ConfigMap, `risk-model` (2 pods, ClusterIP), `scan-api` (3 pods, NodePort), resource limits from SPEC §1.4 *(ConfigMap, risk-model and scan-api are on `main`; the namespace manifest is not yet there and nothing has been applied to a cluster.)*
- [~] Probes: startup, liveness and readiness for `scan-api` (paths from M1, Day 6); `/health` for `risk-model`
- [ ] When M2's Dockerfiles land (Day 7), build the images, `minikube image load`, replace the stand-in; curl through the NodePort must show `source: model`
- [~] `scripts/demo-scale.sh`: 3 → 5 → 2 pods *(`.sh` and `.ps1` are on `main`; not yet run against a cluster.)*

**Days 9–11: integration (host it on your laptop)**
- [ ] Have Minikube running and the repo pulled by Day 9; switch manifests to the Docker Hub images; follow the runbook in TEAM_PLAN

**Days 12–16: demos**
- [ ] ConfigMap demo: change `RISK_THRESHOLD` 0.70 → 0.60 and restart; a borderline URL changes verdict with no code change
- [ ] Record manual scaling **before** adding the HPA, then enable metrics-server, add `30-hpa.yaml` (min 3, max 6, 60% CPU) and load-test with `hey`
- [ ] Rolling update `risk-model` to v2 (M2's tag, Day 14)
- [ ] Rollback demo: deploy M1's v3-broken tag, show the old pods still serving, then `kubectl rollout undo` and `rollout history`
- [ ] `scripts/demo-rollback.sh` and `scripts/load-test.sh`

**Days 17–21: wrap-up**
- [ ] Screenshots: both Ansible runs, pods before/after scaling, `get svc` and curl, probes in `describe pod`, the stuck rollout and undo, HPA under load
- [ ] Compile the Component 2 report from M2's sections and yours; run the full demo twice

---

## Done together

- **Day 1 kickoff (60 min):** agree `SPEC.md`, pick the demo laptop, M1 creates the repo.
- **Integration session (Days 9–11, about 3 hours):** follow the runbook in TEAM_PLAN.
- **Report:** each person writes one paragraph each for Abstract, Introduction, Conclusion and References.
- **Final demo (about 12 min):** M1 4 min → M2 3 min → M3 5 min → shared Q&A. Rehearse on Day 20.
