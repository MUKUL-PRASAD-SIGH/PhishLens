# PhishLens — Team Execution Plan

Last updated: Oct 22, 2026

## Overview

Three members ship PhishLens in 21 days across five phases. Each person owns one slice of the pipeline and hands work to the others at fixed points. The repo README holds the API contracts and system requirements; this plan covers who does what, in what order, and who waits on whom.

| Member | Owns | Marks it covers | Reviews PRs from |
| --- | --- | --- | --- |
| M1 — Application & CI | `scan-api/`, `Jenkinsfile`, `ci/`, `.github/` | Component 1: Git, Maven, Jenkins, CI enhancement | M3 |
| M2 — ML service & containers | `risk-model/`, both Dockerfiles, `docker-compose.yml` | Component 2: Docker, second microservice, multi-stage build, metrics | M1 |
| M3 — Infrastructure & deployment | `ansible/`, `k8s/`, `scripts/` | Component 2: Ansible, Kubernetes, scaling, probes, ConfigMap, rollback, HPA | M2 |

**Assumptions**

- **Timeline:** Day 1 is the kickoff. If the deadline is shorter than 21 days, shrink Phases 1 and 3 first. Never skip Phase 2 (integration).
- **Demo machine:** the 16 GB laptop, assumed to be M3's, hosts integration and the final demo.
- **Image handoff:** Jenkins pushes images to free public Docker Hub repositories, and the cluster pulls them from there. Fallback: build them on the demo machine with the same tags.
- **How to read each step:**
    - *Done when* says how you know the step is finished.
    - *Needs* names what the step waits on, and from whom.
    - *Gives* names what it unblocks for someone else.

## Phase plan

Five phases run over 21 days, and the team moves on only when a phase's gate is met. Everything waits on two handoffs: the Day 2 skeletons, and M2's Day 7 Dockerfiles.

| | Setup · Days 1–2 | Build solo · Days 3–8 | Integrate · Days 9–11 | Enhance · Days 12–16 | Wrap-up · Days 17–21 |
| --- | --- | --- | --- | --- | --- |
| **M1** · App and CI | **Repo + rules, API skeleton** | Scan API + tests, Jenkins on push, SHA versioning | Docker stages, push to Hub, trigger build | Trivy scan, v3-broken image, GitHub Actions | C1 evidence, C1 report |
| **M2** · ML and Docker | **Model skeleton**, pick dataset | Features + v1, **pytest suite, Dockerfiles, compose stack** | Verify images, compose check | Train v2 model, model card, metrics write-up | Docker evidence, report sections |
| **M3** · Infra and K8s | VM + inventory, Minikube up | Ansible role, K8s manifests, probes, scale script | Host session, real images, end-to-end curl | ConfigMap demo, HPA + load test, v2 rollout, rollback demo | C2 evidence, full rehearsal |
| **Gate to move on** | Skeletons on `main` | Jenkins green, images run | curl through NodePort works | All demos recorded | Submit |

**Bold** items are handoffs the other members wait on. Read across a row for one member's work in order. Read down a column for what all three must finish before the next gate.

## Member 1 — Application & CI

M1 builds `scan-api` and the Jenkins pipeline. M1 is on the critical path twice: the API skeleton on Day 2, and the pipeline building images on Day 8.

### Phase 0 · Days 1–2

1. Set up the repo.
    - Create the GitHub repo `phishlens` and add the README.
    - Protect `main`: PRs required, 1 approval.
    - Add `CODEOWNERS`, invite M2, M3 and the course coordinator, and commit `repo_link.txt`.
    - *Done when:* M2 and M3 can each push a branch and open a PR.
2. Build the API skeleton.
    - Generate `scan-api` with Spring Initializr: Java 17, Maven, Web, Validation, Actuator.
    - Add `POST /api/scan` returning a fixed `SAFE` verdict, plus `GET /api/info`.
    - Set `<finalName>scan-api</finalName>` in `pom.xml`.
    - *Done when:* `mvn clean package` produces `target/scan-api.jar` and curl returns JSON.
    - *Gives:* M2 the source for the scan-api Dockerfile, and M3 the JAR for the Ansible role. M2 reviews this PR.

### Phase 1 · Days 3–8

3. Build the real service on `feat/api-scan-endpoint`.
    - Request and response records.
    - `VerdictPolicy`, with thresholds read from config.
    - `ScanHistory`, keeping the last 100 scans, thread-safe.
    - `RiskModelClient`, using `MODEL_TIMEOUT_MS`.
    - `FallbackScorer` and `ScanController`.
    - *Needs:* M2's v1 rule weights (Day 5) to copy into `FallbackScorer`, and M2's skeleton running locally.
    - *Done when:* curl returns `source: model` with risk-model running, and `source: fallback` with it stopped.
4. Add metrics and probes in `application.yml`.
    - Expose `health,info,prometheus` and enable the liveness and readiness groups.
    - Add Micrometer counters `phishlens.scans` (tagged by verdict) and `phishlens.model.fallback`, and the timer `phishlens.model.latency`.
    - *Gives:* M3 the probe paths, and M2 the metric names for the Prometheus write-up (Day 6).
5. Write JUnit tests on `feat/api-tests`.
    - Verdict boundaries at 0.39, 0.40, 0.69 and 0.70.
    - Controller tests with MockMvc: 200 for a good URL, 400 for a bad one.
    - Fallback when the model is down, using `MockRestServiceServer`.
    - *Done when:* `mvn test` passes locally.
6. Build the Jenkins image in `ci/jenkins/Dockerfile`.
    - Start from `jenkins/jenkins:lts-jdk21` and add Maven, Python 3 with venv, and the Docker CLI.
    - Run it with a `jenkins_home` volume and `/var/run/docker.sock` mounted.
    - *Done when:* the Jenkins UI is up, and `docker ps` works inside the container.
7. Write the first `Jenkinsfile`.
    - Trigger: `triggers { pollSCM('* * * * *') }`.
    - Stages: Checkout → Test → Package → Archive (the JAR with `fingerprint: true`, plus `junit` reports).
    - Create the Pipeline job `phishlens-ci`: Pipeline script from SCM, branch `main`.
    - Run it once by hand, because polling only registers after the first run. Then push a commit and watch it start by itself.
    - *Done when:* a push builds automatically within about a minute.
8. Add a parallel Model tests stage.
    - Create a venv, run `pip install -r risk-model/requirements.txt pytest`, then `pytest risk-model/tests`.
    - Guard the stage with `fileExists('risk-model/tests')`.
    - *Needs:* M2's pytest suite (Day 7).
9. Add commit-SHA versioning (the graded CI enhancement).
    - Set `SHORT_SHA` from `git rev-parse --short HEAD`.
    - Archive the JAR as `scan-api-<sha>-b<build>.jar`.
    - Set the build display name to `#<build> <sha>`.

### Phase 2 · Days 9–11

10. Add the Docker stages, guarded by `fileExists('scan-api/Dockerfile')`.
    - Build both images, tagged `<sha>` and `b<build>`, passing `APP_COMMIT=<sha>` as a build arg.
    - Push them to Docker Hub using credentials stored in Jenkins.
    - *Needs:* M2's Dockerfiles (Day 7), and repository names agreed with M3 (Day 8).
    - *Gives:* M3 the real images for the cluster.
11. In the integration session, push the commit whose build the cluster will run.

### Phase 3 · Days 12–16

12. Add a Trivy stage that scans both images and fails on fixable `CRITICAL` findings (`--ignore-unfixed`).
    - *Needs:* M2 to bump base images if anything fixable shows up.
13. Optional: add `.github/workflows/ci.yml` running `mvn test` and `pytest` on every push.
14. Build the broken v3 release on branch `demo/v3-broken`.
    - Make `scan-api` fail at startup, for example by requiring an env var that the ConfigMap doesn't set.
    - The Jenkins job builds `main` only, so build and push this image by hand, tagged with its own SHA.
    - Never merge this branch.
    - *Gives:* M3 the image for the rollback demo (Day 14).

### Phase 4 · Days 17–21

15. Capture the Component 1 evidence, compile the Component 1 report, and rehearse your part of the demo.

## Member 2 — ML service & containers

M2 builds `risk-model` and every Dockerfile. M2's Day 7 handoff — the pytest suite, both Dockerfiles and the compose file — unblocks both other members.

### Phase 0 · Days 1–2

1. Build the model skeleton.
    - Scaffold `risk-model/`: FastAPI, with pinned versions in `requirements.txt`.
    - `POST /predict` returns a fixed `0.5`, and `GET /health` returns ok.
    - *Done when:* `uvicorn app.main:app --port 8000` runs and curl answers.
    - *Gives:* M1 a real endpoint to call. M3 reviews this PR.
2. Choose a public phishing-URL dataset (Kaggle or PhishTank), note its license, and start the download.

### Phase 1 · Days 3–8

3. Write `features.py`.
    - Features: length, digit count, special characters, `@`, IP host, subdomain count, suspicious TLD, HTTPS, entropy.
    - Handle URLs typed without `http://`.
4. Build the v1 heuristic scorer (`MODEL_VERSION=v1`): a weighted sum, clipped to 0–1.
    - *Gives:* M1 the rule weights for `FallbackScorer` (Day 5).
5. Write the pytest suite.
    - `tests/test_features.py`: an IP host is flagged, and a longer URL scores higher.
    - `tests/test_api.py` with `TestClient`: 200 on a good request, 422 when `url` is missing, and `/health`.
    - *Gives:* M1 the command `pytest risk-model/tests` for the Jenkins test stage (Day 7).
6. Write `risk-model/Dockerfile`.
    - Base image `python:3.12-slim`, with `pip install --no-cache-dir`.
    - Run as a non-root user, with uvicorn on `${PORT}`.
7. Write `scan-api/Dockerfile` as a multi-stage build.
    - Build stage, `maven:3.9-eclipse-temurin-17`: copy `pom.xml` and resolve dependencies first, then copy `src` and package.
    - Runtime stage, `eclipse-temurin:17-jre`: run `scan-api.jar` as non-root, with `ARG`/`ENV APP_COMMIT`.
    - Add `.dockerignore` files to both services.
    - Build a single-stage version once and record both image sizes for the report.
    - *Needs:* M1's skeleton source. M1 reviews this Dockerfile.
8. Write `docker-compose.yml` with both services and `MODEL_URL=http://risk-model:8000`.
    - *Done when:* after `docker compose up --build`, a curl to port 8080 returns `source: model`.
    - *Gives:* M1 a one-command local setup, and M3 proof that the images run before they go to Kubernetes (Day 7).
9. Add metrics.
    - Expose `/metrics` with `prometheus-fastapi-instrumentator`.
    - Add the counter `phishlens_predictions_total{model_version}`.

### Phase 2 · Days 9–11

10. In the integration session, build both images and run compose on the demo machine before anyone touches the cluster.

### Phase 3 · Days 12–16

11. Train the v2 model.
    - `train.py` splits the dataset, trains logistic regression (try random forest too), and saves `model.pkl` with joblib.
    - Pin the scikit-learn version used for training, and keep the file small.
    - Write `MODEL_CARD.md` with v1 and v2 accuracy, precision and recall on the same test split.
    - *Done when:* v2 beats v1 on that split, and `/predict` returns `modelVersion: v2`.
    - *Gives:* M3 the v2 image tag for the rolling-update demo (Day 14).
12. Update base images if M1's Trivy stage flags fixable `CRITICAL` issues.
13. Write the Prometheus explanation: a sample scrape config for `/actuator/prometheus` and `/metrics`, and what each metric tells you.

### Phase 4 · Days 17–21

14. Capture the Docker and model evidence, write your report sections, and rehearse your part of the demo.

## Member 3 — Infrastructure & deployment

M3 owns the Ansible host, the cluster and the demo machine, and runs the Component 2 demo. M3 waits on others the most, so M3 works against stand-ins until the real pieces arrive.

### Phase 0 · Days 1–2

1. Set up the Ansible target VM.
    - Run `multipass launch 22.04 --name phishlens-vm --cpus 1 --memory 1G --disk 10G`.
    - Add your SSH public key to the VM and write `ansible/inventory.ini`.
    - *Done when:* `ansible all -i inventory.ini -m ping` returns pong.
    - On Windows, if WSL can't reach the VM's IP, switch to a VirtualBox VM with a host-only adapter. As a last resort, target WSL itself, with systemd enabled and `ansible_connection=local`.
2. Prove the cluster works.
    - Run `minikube start --cpus=4 --memory=6g`.
    - Apply `k8s/00-namespace.yaml` and a stand-in nginx Deployment.
    - M1 reviews this PR.

### Phase 1 · Days 3–8

3. Create the role with `ansible-galaxy init roles/scan_api`.
    - Defaults: `app_port`, `app_user`, `app_dir`, `jar_src`, `model_url`, `risk_threshold`.
4. Write the role's tasks and handler.
    - Tasks: install `openjdk-17-jre-headless`, create the user and `/opt/phishlens`, copy the JAR, render `phishlens.service.j2`, then enable and start the service.
    - Handler: `restart phishlens` with `daemon_reload`, notified by the copy and template tasks.
    - *Needs:* M1's skeleton JAR (Day 2).
    - *Done when:* the first run reports changes, `curl <vm-ip>:8080/api/info` answers, and a second run reports `changed=0`.
5. Write the manifests. Use the resource requests and limits from README §3.4.
    - `01-configmap.yaml`.
    - `10-risk-model.yaml`: 2 replicas, ClusterIP on port 8000.
    - `20-scan-api.yaml`: 3 replicas, NodePort, `envFrom` the ConfigMap.
6. Add probes.
    - `scan-api`: a startupProbe so the JVM has time to boot, then liveness on `/actuator/health/liveness` and readiness on `/actuator/health/readiness`.
    - `risk-model`: `/health`.
    - *Needs:* M1's probe paths (Day 6).
7. Switch to the real images as soon as M2's Dockerfiles merge (Day 7).
    - Build both images locally, `minikube image load` them, and replace the stand-in.
    - *Done when:* 3 + 2 pods are Running, and a curl through the NodePort returns `source: model`.
8. Write `scripts/demo-scale.sh`: show pods, scale to 5, show pods, scale to 2, show pods.

### Phase 2 · Days 9–11

9. Host the integration session on your machine (see the runbook), and switch the manifests to the Docker Hub images Jenkins pushed.

### Phase 3 · Days 12–16

10. ConfigMap demo.
    - Lower `RISK_THRESHOLD` from 0.70 to 0.60 and run `kubectl rollout restart`.
    - Show a borderline URL changing verdict, with no code change.
11. HPA.
    - Enable the `metrics-server` addon and add `30-hpa.yaml` (min 3, max 6, 60% CPU).
    - Load-test with `hey` and watch `kubectl get hpa -w`.
    - Record the manual scaling demo before applying the HPA, because the HPA overrides `kubectl scale`.
12. Rolling update to v2.
    - `kubectl set image` for `risk-model` with M2's v2 tag, annotate `change-cause`, then `kubectl rollout status`.
    - *Needs:* M2's v2 tag (Day 14).
13. Rollback demo.
    1. Set `scan-api` to M1's v3-broken tag.
    2. Show the new pod in `CrashLoopBackOff` while the 3 old pods keep serving.
    3. Run `kubectl rollout undo`, then `kubectl rollout history`.
    - *Needs:* M1's v3 tag (Day 14).
14. Write `scripts/demo-rollback.sh` and `scripts/load-test.sh`, so each demo runs with one command.

### Phase 4 · Days 17–21

15. Capture the Component 2 evidence, compile the Component 2 report from M2's sections and yours, and run the full demo twice.

## Handoffs and dependencies

Eleven handoffs connect the three flows. Each has a latest day and a workaround, so a late handoff slows the plan but never stops it.

| # | Handoff | From → To | Latest day | If it's late |
| --- | --- | --- | --- | --- |
| 1 | scan-api skeleton source and JAR | M1 → M2, M3 | Day 2 | M3 uses any Spring Boot sample JAR; M2 drafts the Dockerfile against a plain Initializr project |
| 2 | risk-model skeleton | M2 → M1 | Day 2 | M1 builds and tests on the fallback path only |
| 3 | v1 rule weights | M2 → M1 | Day 5 | M1 ships three simple fallback rules: IP host, `@` sign, URL length |
| 4 | Probe paths and metric names | M1 → M2, M3 | Day 6 | Use the values in README §4 and §5 |
| 5 | pytest suite and `requirements.txt` | M2 → M1 | Day 7 | The model test stage stays guarded off; JUnit alone still meets the test enhancement |
| 6 | Both Dockerfiles and the compose file | M2 → M1, M3 | Day 7 | M3 keeps the nginx stand-in; the Jenkins Docker stage stays off |
| 7 | Docker Hub repository names and tag format | M1 ↔ M3 | Day 8 | Build on the demo machine with the same tags and `minikube image load` |
| 8 | Demo machine ready (Minikube up, repo pulled) | M3 → all | Day 9 | Integrate on compose first, then the cluster the next day |
| 9 | v2 image tag | M2 → M3 | Day 14 | Demo the rolling update with a rebuilt v1 under a new tag |
| 10 | v3-broken image tag | M1 → M3 | Day 14 | M3 sets a tag that doesn't exist; `ImagePullBackOff` still shows the stalled rollout and the undo |
| 11 | Report sections and screenshots | All → M1 (Component 1), M3 (Component 2) | Day 18 | The compiler leaves a marked placeholder and flags it in chat |

## Where each member needs help

Most blockers sit at the edges between folders, so the person who owns the other side is the one to ask. Ask after two hours stuck, with the error message and the command you ran.

| Who's stuck | On what | Ask | What they bring |
| --- | --- | --- | --- |
| M1 | Model test stage fails in Jenkins (pip, venv, import paths) | M2 | The exact `requirements.txt` and pytest command; a check that tests pass outside Jenkins |
| M1 | Docker stage fails (build context, Dockerfile path, image won't start) | M2 | Fixes the Dockerfile; confirms the build with `docker build` locally |
| M1 | Image names or tags don't match what the cluster pulls | M3 | The names the manifests expect; agree once, then both copy them from README §5.4 |
| M2 | Maven build inside the scan-api Dockerfile (dependency caching, JAR name) | M1 | `pom.xml` details: `finalName`, plugins, the build command |
| M2 | JVM runs out of memory in the container | M1 + M3 | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` and the 512Mi limit |
| M2 | Model loads locally but not in the image (paths, scikit-learn version) | M3 | Runs the image on the cluster and reads the pod logs together |
| M3 | Probes fail and pods keep restarting | M1 | Confirms the Actuator probe settings; startup timing for the startupProbe |
| M3 | Pods crash after an image switch (env vars, port, file permissions) | M2 | The image's expected env vars, port and user |
| M3 | Demo images for v2 and v3 | M2 (v2), M1 (v3) | The tags by Day 14 |
| Everyone | Report wording, screenshots, references | The report compiler: M1 for Component 1, M3 for Component 2 | One consistent format and section order |

## Integration day runbook

Integration is one joint session of about three hours, on the demo machine, between Days 9 and 11. It is done when `/api/info` through the NodePort shows the same commit SHA as the latest Jenkins build, and `/api/scan` returns `source: model`.

**Before the session**

- [ ] All Phase 1 PRs are merged to `main`.
- [ ] Jenkins is green on the latest commit.
- [ ] Both images are on Docker Hub.
- [ ] M3 has Minikube running with `--cpus=4 --memory=6g`.

**Steps**

1. **M3** pulls `main` and enables the `metrics-server` addon. *Check:* `kubectl get nodes` shows Ready.
2. **M2** runs `docker compose up --build` on the demo machine and curls port 8080. *Check:* `source: model`. This proves the images work before Kubernetes is involved.
3. **M1** pushes a small commit and watches Jenkins test, build and push the images. *Check:* new tags `<sha>` and `b<build>` appear on Docker Hub.
4. **M3** runs `kubectl apply -f k8s/`, then `kubectl set image` on both Deployments to the new SHA. *Check:* `kubectl get pods -n phishlens` shows 3 + 2 Running.
5. **M3** opens the service with `minikube service scan-api -n phishlens --url`, in a second terminal on Docker Desktop. **M1** curls `/api/info`. *Check:* the commit matches the Jenkins build.
6. **M2** curls `/api/scan` with a suspicious URL. *Check:* `source: model`, which proves `scan-api` reaches `risk-model` through cluster DNS.
7. **M3** dry-runs the scaling script: 3 → 5 → 2.
8. **All three** list what broke, open a GitHub issue for each, assign an owner, and agree the Phase 3 order.

## Final demo flow

The demo runs about 12 minutes in pipeline order: code → build → package → deploy → scale → recover. Each member presents their own part and answers questions on it.

1. **M1 · 4 min — Code to build**
    - The architecture, from the README diagram.
    - Git history showing the branches and PR merges.
    - A live push that Jenkins picks up on its own.
    - Test results, then the archived JAR named with the commit SHA.
2. **M2 · 3 min — Packaging and the model**
    - The multi-stage Dockerfile and the image-size comparison.
    - `docker compose up` working end to end.
    - The v1 vs v2 model card.
    - The `/metrics` output.
3. **M3 · 5 min — Configure, deploy, recover**
    - An Ansible run, then a second run showing `changed=0`.
    - `kubectl get pods,svc`, then a curl through the NodePort.
    - Scaling 3 → 5 → 2.
    - The HPA reacting to load.
    - The rollout undo of v3.
4. **All · Q&A** — each member answers for their own area.

**Backups**

- Start Minikube and pre-pull every image before the session begins.
- Stop Jenkins once M1's part is done, to free memory for the cluster.
- Keep a screen recording of each part in case a live step fails.

## Evidence checklist

Every item the assignment asks you to capture has one owner. Capture each item the day its step is done, and save it to `docs/report/screenshots/<component>/`. The code gets checked against these screenshots, so capture them from the real runs.

**M1 — Component 1**

- [ ] Git graph showing the branches and a merge (`git log --graph --oneline --all`)
- [ ] Jenkins job configuration: SCM, poll trigger, build steps
- [ ] Console output of a successful build that was triggered automatically
- [ ] The archived artifact, named with the commit SHA
- [ ] Test stage passing, with the JUnit results page
- [ ] Optional: Trivy output and the GitHub Actions run

**M2 — Component 2, containers**

- [ ] Both Dockerfiles and a successful `docker build`
- [ ] `docker images` showing single-stage vs multi-stage sizes
- [ ] Compose running, with a curl returning `source: model`
- [ ] Model card with the v1 and v2 metrics
- [ ] `/metrics` and `/actuator/prometheus` output

**M3 — Component 2, deployment**

- [ ] `ansible-playbook` output: first run with changes, second run with `changed=0`
- [ ] `kubectl get pods` before and after scaling to 5, and back to 2
- [ ] `kubectl get svc`, and a curl response through the NodePort
- [ ] `kubectl describe pod` showing the probes, and `kubectl get configmap`
- [ ] The rollout stuck on v3, then `rollout undo` and `rollout history`
- [ ] `kubectl get hpa -w` under load

## Risks and fixes

These are the blockers most likely to cost a day. Each has a known fix and an owner.

| Risk | What you'll see | Fix | Owner |
| --- | --- | --- | --- |
| Polling never fires | Pushes don't start builds | Run the job once by hand so Jenkins registers the `pollSCM` trigger | M1 |
| Jenkins can't run Docker | `permission denied` on `docker.sock` | Mount the socket and add the Jenkins user to the socket's group | M1 |
| JVM pods restart during startup | `CrashLoopBackOff` with liveness failures | Add a startupProbe with enough time for the JVM to boot | M3 (M1 helps) |
| Local image not found | `ErrImagePull` or `ImagePullBackOff` | Use `imagePullPolicy: IfNotPresent` and the exact SHA tag; never `:latest` | M3 |
| 5th pod won't schedule | Pod stuck in `Pending` | Start Minikube with `--cpus=4`, or lower the scan-api CPU request to 150m | M3 |
| HPA undoes manual scaling | Replica count jumps back | Record the manual scaling demo before applying the HPA | M3 |
| Model fails to load in the image | `/health` shows `modelLoaded: false` | Pin the same scikit-learn version for training and serving | M2 |
| WSL can't reach the VM | Ansible ping times out | Use a VirtualBox host-only network, or target WSL with systemd | M3 |
| Laptop runs out of memory | Everything slows or the cluster crashes | Run Jenkins and Minikube one at a time on 8 GB machines | All |
| A teammate goes quiet | Handoff missed | Use the workaround in the handoffs table and keep going | Next member in line |

## Communication rhythm

The team needs one daily line each and six short syncs; everything else happens in PRs and GitHub issues.

**Daily update.** Each evening, everyone posts one line in the group chat: done today, next, blocked on.

**Syncs**

| Day | Length | Purpose |
| --- | --- | --- |
| Day 1 | 60 min | Kickoff: agree the README contracts, set up the repo, choose the demo machine |
| Day 5 | 15 min | Contract check: fallback rules, probe paths, metric names |
| Day 8 | 15 min | Pre-integration: image names, Docker Hub access, session time |
| Day 13 | 15 min | Enhancements: confirm the v2 and v3 tags arrive by Day 14 |
| Day 17 | 30 min | Report: section owners, screenshot gaps |
| Day 20 | 45 min | Dress rehearsal of the full demo |

**Rules**

- **Blocked for more than two hours:** post the error and the command you ran, and tag the helper named in "Where each member needs help".
- **Requests to another member:** open a GitHub issue labelled with that member's area, not a chat message.
- **Merging:** never merge your own PR without the review named in the overview table.
- **Contract changes:** a README PR approved by all three, before any code changes.
