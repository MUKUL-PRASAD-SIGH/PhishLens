# Component 2: Configuration Management, Containers and Orchestration (Part B: Deployment)

**Project:** PhishLens, a malicious-URL risk scanner
**Component:** 2 of 2. This part covers Ansible and Kubernetes; the Docker, model and metrics sections are written by M2 and are not repeated here.
**Author:** M3 (Infrastructure and Deployment)
**Repository:** https://github.com/MUKUL-PRASAD-SIGH/PhishLens (also in `repo_link.txt`)

> **OPEN ITEMS BEFORE THIS REPORT IS FINAL (placeholders, to be resolved by the team):**
> 1. **Course code: NOT CONFIRMED.** Same open item as the Component 1 report. Placeholder: **[COURSE CODE]**.
> 2. **Title page and certificate: NOT INCLUDED.** Placeholder: **[TITLE PAGE AND CERTIFICATE: insert from template]**.
> 3. **M2's sections (Docker, multi-stage build, model, metrics): NOT INCLUDED.** Placeholder: **[M2 SECTIONS]**. Figure numbers below start at D1 so they can be renumbered once M2's figures are in.
> 4. **Ansible target details to confirm:** the Ubuntu Server release on the VM **[UBUNTU VERSION]** and the Ansible version in the `ansible221` virtual environment **[ANSIBLE VERSION: run `ansible --version` in the venv]**.
> 5. **Evidence still to capture** (the procedures are in `docs/report/M3_audit_and_handoff.md`): a first Ansible run on a clean target, screenshots of the scaling and rollback demos (both are currently evidenced by captured event logs only), the ConfigMap threshold demo, and a load test against the HPA.
> 6. **HPA target:** the manifest uses 70% CPU and the team plan says 60%. Section 3 explains the effect; **[CONFIRM: keep 70% or change to 60% before the demo]**.
>
> Figure paths are relative to the repository root. Screenshots are in `evidence/ansible/` and `evidence/k8s/`; captured command output is in `evidence/k8s/text-20261105-205710/` (see its README). The team plan names `docs/report/screenshots/component2/` as the location; the compiler may move them there.

---

## Abstract

This part of Component 2 deploys PhishLens in two ways. First, an Ansible role installs Java 17 on an Ubuntu Server VM, copies the `scan-api` JAR and runs it as a systemd service; a second run of the same playbook reports `changed=0`. Second, both services run on a single-node Minikube cluster from the images that the Jenkins pipeline pushed to Docker Hub, tagged with commit `43a2986`. All configuration comes from one ConfigMap. Startup, liveness and readiness probes gate traffic, `scan-api` is exposed through a NodePort, and it reaches `risk-model` through cluster DNS. Requests through the NodePort return `"source":"model"` and `"modelVersion":"v2"`. I scaled `scan-api` by hand from 3 to 4 and back to 3, added a HorizontalPodAutoscaler, and rolled back a deliberately broken v3 release with `kubectl rollout undo` while the three healthy pods kept running. The laptop's limits (8 GB of RAM; Minikube capped at 2 CPUs and 2 GiB) changed parts of the plan. The scaling demo stops at 4 pods, not 5. The HPA is configured and reading metrics but has not been load-tested, so automatic scale-up is **not** demonstrated. The ConfigMap threshold demo and the v1-to-v2 rolling update are prepared but not yet performed. Section 3 lists every deviation.

## 1. Introduction

The assignment asks for configuration management with Ansible and orchestration with Kubernetes: deploy the application, show that the configuration is repeatable, scale it, and recover from a bad release. This part does that for both PhishLens services:

- `scan-api` (Java 17, Spring Boot, written by M1) is the public API. `POST /api/scan` returns a score and a verdict; `GET /api/info` returns the service name, version and the commit the image was built from.
- `risk-model` (Python, FastAPI, written by M2) scores a URL. `scan-api` calls it and falls back to local rules if it is slow or down, which the response reports as `"source":"fallback"`.

I did not build either image; the Jenkins pipeline (Component 1) builds, scans and pushes them, and I deploy the tags it produced. Where my work depends on the others' (the images, the probe paths, the broken v3 image), I say so.

## 2. Implementation

### 2.1 Environment

| Item | Version / detail |
|---|---|
| Machine | Windows 11 Pro laptop, 8 GB RAM |
| Linux tooling | WSL2, Ubuntu 24.04: Git, Ansible, Python checks |
| Ansible | **[ANSIBLE VERSION]** in a virtual environment `ansible221`; the syntax check during the audit ran with ansible-core 2.16.3 |
| Ansible target | Ubuntu Server **[UBUNTU VERSION]** on VirtualBox, `192.168.0.109`, SSH key login as `ubuntu`, Python `/usr/bin/python3.14` |
| Containers | Docker Desktop 29.9.2 (WSL2 backend) |
| Kubernetes | Minikube v1.39.0, Docker driver, Kubernetes v1.37.0, containerd 2.3.4; kubectl v1.36.1 run from PowerShell |
| Minikube limits | 2 CPUs and 2 GiB, as set on the Minikube container by Docker (evidence file 14) |
| Add-ons | `metrics-server` |
| Images | `nikitaravindra/scan-api:43a2986`, `nikitaravindra/risk-model:43a2986` (Jenkins build #8), and `nikitaravindra/scan-api:v3-broken-c1670ef` for the rollback demo |

The team plan assumed a 16 GB demo laptop with `minikube start --cpus=4 --memory=6g` and a Multipass VM. The machine has 8 GB, so Minikube runs with 2 CPUs and 2 GiB, and the VM and Minikube are not run at the same time. Section 3 describes what that changed.

### 2.2 Code

| Path | Purpose |
|---|---|
| `ansible/inventory.ini` | One host, `ubuntu-vm`, in group `scan_servers` |
| `ansible/playbook.yml` | Applies the `scan_api` role with `become: true` |
| `ansible/roles/scan_api/defaults/main.yml` | Variables: user, directory, JAR source, port, `MODEL_URL`, `RISK_THRESHOLD`, `MODEL_TIMEOUT_MS`, Java package |
| `ansible/roles/scan_api/tasks/main.yml` | apt cache, Java 17, user and group, `/opt/phishlens`, JAR check, JAR copy, systemd unit, enable and start |
| `ansible/roles/scan_api/handlers/main.yml` | `Restart phishlens`, with `daemon_reload` |
| `ansible/roles/scan_api/templates/phishlens.service.j2` | systemd unit; configuration passed as `Environment=` lines |
| `k8s/00-namespace.yaml` | Namespace `phishlens` |
| `k8s/configmap.yaml` | ConfigMap `phishlens-config` |
| `k8s/risk-model.yaml` | Deployment (2 replicas) and ClusterIP Service on port 8000 |
| `k8s/scan-api.yaml` | Deployment (3 replicas) and NodePort Service, 8080 → node port 30080 |
| `k8s/hpa.yaml` | HorizontalPodAutoscaler for `scan-api`: min 3, max 6, 70% CPU |
| `scripts/demo-scale.sh`, `demo-scale.ps1` | Manual scaling demo; refuses to run while the HPA exists unless told to suspend it |
| `scripts/demo-rollback.sh` | Rollback demo with pre-flight checks; undoes the rollout itself if interrupted |
| `scripts/demo-configmap.sh` | ConfigMap threshold demo; restores the original value afterwards |
| `scripts/load-test.sh` | HPA load test with `hey`; reports a scale-up only if the replica count rose |
| `scripts/capture-evidence.sh` | Read-only capture of the cluster state used as evidence |

### 2.3 Description

#### 2.3.1 Ansible

The playbook runs one role, `scan_api`, against the VM. The role installs `openjdk-17-jre-headless`, creates a `phishlens` system user with no login shell, creates `/opt/phishlens`, and copies the JAR there. Before copying, a task on the controller checks that the JAR exists and stops with a clear message if it does not. The service runs as a systemd unit rendered from a template: `ExecStart` runs `java -XX:MaxRAMPercentage=75 -jar /opt/phishlens/scan-api.jar`, and the configuration (`SERVER_PORT`, `MODEL_URL`, `RISK_THRESHOLD`, `MODEL_TIMEOUT_MS`) is passed as `Environment=` lines from role variables, so the same JAR can be reconfigured without rebuilding it. `Restart=on-failure` restarts it if it crashes.

Idempotency comes from the modules, not from scripts. `apt`, `user`, `file`, `copy` and `template` each compare the desired state with the host and change nothing if they already match. The JAR copy and the unit template both notify the `Restart phishlens` handler, so the service is restarted only when one of them actually changes and never on a run that changes nothing.

The JAR deployed to the VM is the early `scan-api` skeleton built from this branch on 4 November, as the plan specifies for this step. It answers `/actuator/health` but has no model client, and no `risk-model` runs on the VM. The full service runs on Kubernetes (2.3.2).

**Runs (Figures D1–D5).** The run in Figures D1–D2 ends `ok=11 changed=1`. The one changed task is "Update apt package index"; every deployment task reports `ok`, because Java, the user, the JAR and the service were already in place from an earlier run that was not captured. A second run straight afterwards ends `ok=11 changed=0` (Figures D3–D4). Figure D5 shows `systemctl is-active phishlens` returning `active` and `/actuator/health` returning HTTP 200 with `{"status":"UP"}` on 5 November at 13:21 GMT.

So the figures show idempotency (`changed=0`), but they do not show a first deployment onto a clean host. A capture of that is an open item. There is also a caveat on `changed=0`: it holds only within an hour of the last run. The apt task uses `cache_valid_time: 3600`, so once the cache is older than that, the task refreshes it and reports `changed`, even though nothing about the deployment changed. That is what happened in the run in Figure D2.

#### 2.3.2 Kubernetes architecture

```
 client ──▶ NodePort 30080 ──▶ Service scan-api (8080) ──▶ 3 × scan-api pods
                                                              │  MODEL_URL=http://risk-model:8000
                                                              ▼  (cluster DNS)
                                    Service risk-model (ClusterIP 8000) ──▶ 2 × risk-model pods
 ConfigMap phishlens-config ──envFrom──▶ both Deployments
 HPA scan-api-hpa ──▶ Deployment scan-api (3–6 replicas, 70% CPU)
```

Everything is in the namespace `phishlens`. `risk-model` is internal only (ClusterIP); `scan-api` is the only entry point (NodePort 30080). Both Deployments select their pods by an `app` label, and both Services target the named container port `http`, so a port change is made in one place.

**Resources.** Requests and limits follow the budget in SPEC §1.4 exactly:

| Workload | Replicas | CPU request / limit | Memory request / limit |
|---|---|---|---|
| `scan-api` | 3 | 250m / 500m | 384Mi / 512Mi |
| `risk-model` | 2 | 100m / 250m | 128Mi / 256Mi |

`scan-api` also sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, so the JVM sizes its heap from the 512Mi container limit instead of the node's memory.

**Images.** Both Deployments use the commit-tagged images from Jenkins build #8 (`43a2986`), never `:latest` (Figure D7). The tag is the Component 1 enhancement doing its job here: `GET /api/info` through the NodePort returns `"commit":"43a2986"` (evidence file 15). That is the same value as the Jenkins build name, the archived JAR and the Docker Hub tag in the Component 1 report, so the running pods can be traced to their commit. The first manifests used locally built `phishlens/*:dev` images (rollout revisions 1–2); I switched to the Docker Hub images on 5 November (revision 3 of each Deployment). `imagePullPolicy` is `Always`, which is safe with immutable SHA tags but means every pod start contacts Docker Hub (see section 3).

The live cluster matches the files in `k8s/`: `kubectl diff` reports no differences for any manifest (evidence file 19).

#### 2.3.3 ConfigMap

Both Deployments load every key of `phishlens-config` as environment variables (`envFrom`), so no value is baked into an image:

| Key | Value | Used by |
|---|---|---|
| `SERVER_PORT` | `8080` | scan-api |
| `MODEL_URL` | `http://risk-model:8000` | scan-api: the ClusterIP Service name, resolved by cluster DNS |
| `RISK_THRESHOLD` | `0.70` | scan-api: score at or above this is `MALICIOUS` |
| `MODEL_TIMEOUT_MS` | `3000` | scan-api: budget before falling back (300 ms connect + the rest read) |
| `PORT` | `8000` | risk-model |
| `MODEL_PATH` | `/app/model/model.pkl` | risk-model |
| `MODEL_VERSION` | `v2` | risk-model: trained model instead of the v1 heuristic |

Evidence file 05 shows the ConfigMap and file 06 shows the values a running `scan-api` container sees. Two choices differ from the SPEC defaults:

- `MODEL_TIMEOUT_MS` is 3000, not 800. With 800 ms, requests that `risk-model` could have answered were falling back to the local rules, so I raised it.
- `APP_VERSION` and `APP_COMMIT` are deliberately **not** in the ConfigMap. An earlier version set `APP_COMMIT: "local"`, which would override the commit baked into the image and make `/api/info` report `local` instead of `43a2986`.

A ConfigMap change is not picked up by running pods, because environment variables are read when the container starts. After changing it, `kubectl rollout restart` replaces the pods one at a time. The planned demo of this (lowering `RISK_THRESHOLD` to 0.60 so a borderline URL changes verdict) is prepared but not yet performed; see 2.3.8.

#### 2.3.4 Health probes

| | Startup | Readiness | Liveness |
|---|---|---|---|
| `scan-api` | `/actuator/health`, every 5 s, 60 failures allowed (5 min) | `/actuator/health/readiness`, every 10 s, 3 failures | `/actuator/health/liveness`, every 20 s, 3 failures |
| `risk-model` | `/health`, every 5 s, 60 failures | `/health`, every 10 s, 3 failures | `/health`, every 20 s, 3 failures |

All probes have a 3 s timeout (evidence files 02–04). The startup probe holds back the other two until the JVM has booted, so a slow start is not mistaken for a hung process and killed. Readiness decides whether a pod receives traffic from the Service; liveness decides whether the kubelet restarts it.

The paths come from M1's `application.yml`. Readiness checks only the application's own state, not `risk-model`, by design. If the model is down, `scan-api` still answers using its fallback rules, so a model outage must not take the API out of the Service. `risk-model`'s `/health` returns 503 if the model file failed to load, so a pod with a broken model never becomes ready.

The probes were tested by a real event on 5 November at about 20:42 IST: every pod's probes timed out at the same moment (`context deadline exceeded`), the liveness probe restarted one `scan-api` pod, and the HPA briefly could not read metrics (evidence files 04 and 13). Because all five pods and metrics-server were affected together, this looks like the whole Minikube node stalling, which is consistent with the memory pressure described in section 3, rather than a fault in either service. I have not proven the cause. The restarted pod passed its startup probe and rejoined without intervention.

#### 2.3.5 Integration between scan-api and risk-model

`scan-api` reaches `risk-model` by the Service name in `MODEL_URL`, so cluster DNS and the ClusterIP Service spread its calls over the two model pods. A scan through the NodePort returns `"source":"model"` and `"modelVersion":"v2"`, which proves the whole path: client → NodePort → `scan-api` pod → DNS → `risk-model` Service → model pod. It was first checked through `kubectl port-forward`, which was not captured, and then on 5 November through the NodePort in two ways:

1. **From inside the Minikube node** (`minikube ssh`, then `curl http://192.168.49.2:30080/...`): `/api/info` returns commit `43a2986`, `/actuator/health` returns `UP`, and `/api/scan` returns `source: model`, `v2` (evidence files 15 and 16).
2. **From the Windows host**, through the tunnel that `minikube service scan-api -n phishlens --url` opens to the NodePort (the method in the team plan's integration runbook): the same answers (evidence file 18).

A direct request from Windows to `192.168.49.2:30080` times out (evidence file 17). With the Docker driver on Windows, the Minikube node's IP is not routable from the host, which is why the tunnel is needed.

One observation I have not analysed: in a batch of scans at about 20:48 IST, shortly after the probe timeouts above, the first request fell back to the local rules and the first three requests completed about 12 s apart. Later requests completed within a second. Even with the 3000 ms budget, a fallback can still happen when the node is under pressure.

#### 2.3.6 Scaling

Manual scaling uses `kubectl scale` followed by `kubectl rollout status`; `scripts/demo-scale.sh` (and `.ps1`) does this for a sequence of replica counts and prints the pods after each step. On 5 November at about 20:12 IST I scaled `scan-api` from 3 to 4 and back to 3. The event log records `Scaled up replica set scan-api-688b576665 from 3 to 4` and, about a minute later, `Scaled down ... from 4 to 3` (evidence file 13). This was done before the HPA was created (20:13:25 IST, Figure D8), because an HPA with `minReplicas: 3` would immediately override a manual change. The script now refuses to run while the HPA exists unless `--suspend-hpa` is given; it then deletes the HPA and re-applies `k8s/hpa.yaml` when it finishes.

The plan asked for 3 → 5 → 2. I stopped at 4 because of the memory limit described in section 3. A screenshot of the pods at 4 replicas has not been taken; the event log is the only record so far.

#### 2.3.7 Autoscaling (HPA)

`k8s/hpa.yaml` (autoscaling/v2) scales `scan-api` between 3 and 6 replicas on average CPU utilisation, with a 70% target. Utilisation is measured against the CPU request, so 70% of 250m is about 175m per pod. The `metrics-server` add-on is enabled and running (evidence file 12), and the HPA reads metrics successfully: `ScalingActive True, ValidMetricFound`, with the current utilisation at 3–8% at idle and 3 current / 3 desired replicas (Figure D8, evidence file 10).

**Automatic scale-up has not been demonstrated.** No load test has been run, so the HPA has only ever held the minimum of 3 replicas. `scripts/load-test.sh` is written for this. It drives `POST /api/scan` through the `minikube service` tunnel (not `kubectl port-forward`, which sends every request to one pod) with `hey`, samples the HPA and `kubectl top` every 15 s, and reports a scale-up only if the replica count actually rose. `hey` is not installed on the laptop yet. Whether the HPA can add pods on this machine is itself doubtful (section 3).

#### 2.3.8 ConfigMap demo (prepared, not performed)

`scan-api` decides the verdict from the model's score: `MALICIOUS` at or above `RISK_THRESHOLD`, `SUSPICIOUS` from 0.40 up to it, and `SAFE` below 0.40. A URL whose score is between 0.60 and 0.70 is therefore `SUSPICIOUS` at the current threshold and should become `MALICIOUS` at 0.60, with no code or image change. I found such a URL by scanning candidates through the NodePort: `http://shop.example.net/item?id=123456789` scores 0.64 with `source: model`, `v2` (evidence file 16; repeated scans gave the same score). `scripts/demo-configmap.sh` backs up the ConfigMap and re-checks the live score before changing anything. It then patches `RISK_THRESHOLD` to 0.60, restarts `scan-api`, scans again, and restores 0.70 at the end, or on any error. It has been run only in its read-only check mode.

#### 2.3.9 Rollback

M1 built a deliberately broken v3 from the unmerged branch `demo/v3-broken`: `scan-api` refuses to start unless `REQUIRED_RELEASE_TOKEN` is set, and the ConfigMap does not set it. The variable was intentionally not added; the failure is the point.

On 5 November at 20:14 IST I set the `scan-api` image to `nikitaravindra/scan-api:v3-broken-c1670ef`. The Deployment uses the default RollingUpdate strategy. With 3 replicas, 25% max surge rounds up to 1 and 25% max unavailable rounds down to 0, so Kubernetes started one new pod and kept all three old ones. The new pod failed at startup and went into `CrashLoopBackOff`. The event log records the one surge pod, repeated pulls of the v3 image, a failed startup probe and `Back-off restarting failed container scan-api` (evidence file 13). The old ReplicaSet `scan-api-688b576665` was never scaled down, so three healthy pods stayed in service throughout.

At about 20:19 IST I ran `kubectl rollout undo deployment/scan-api -n phishlens`. The event log records the v3 ReplicaSet being scaled from 1 to 0, and the Deployment returned to `nikitaravindra/scan-api:43a2986` with 3/3 ready. `kubectl rollout history` shows revisions 1, 2, 3, 5 and 6 (evidence file 07). There is no revision 4 because `rollout undo` re-uses the previous ReplicaSet and renumbers it. Revision 4 (`688b576665`, `43a2986`) became revision 6, and revision 5 is the v3 image (evidence file 09).

The `CHANGE-CAUSE` column is empty, because no `kubernetes.io/change-cause` annotation was set. `scripts/demo-rollback.sh` now sets one. It also checks that the image exists and that the current rollout is healthy before starting, waits for `CrashLoopBackOff` (or `ImagePullBackOff`), shows the stalled rollout and the failing pod's log, proves the old pods still answer through the NodePort, and undoes the rollout. If it is interrupted after the broken image is applied, it runs the undo itself. There is no screenshot of the `CrashLoopBackOff` itself yet; the captured events are the record.

#### 2.3.10 Rolling update of risk-model to v2 (not performed as planned)

The plan asked for a rolling update of `risk-model` from v1 to v2 with `kubectl set image`. That did not happen as a v1 → v2 change. The model version is selected by `MODEL_VERSION` in the ConfigMap, which has been `v2` since the first manifest, and M2's Docker Hub image `43a2986` already contains the v2 model. The rollout history of `risk-model` has three revisions (evidence files 08, 09): two with the local `phishlens/risk-model:dev` image and one with `nikitaravindra/risk-model:43a2986`. Revision 2 → 3 was a real rolling image update, but every committed version of the ConfigMap sets `MODEL_VERSION=v2`, so it did not change the model version. A genuine v1 → v2 rollout can still be shown by overriding `MODEL_VERSION=v1` on the Deployment and then removing the override (procedure in the handoff file); it has not been done.

## 3. Deviations from the plan, and why

| Planned | Actual | Reason / effect |
|---|---|---|
| Multipass VM | VirtualBox Ubuntu Server VM | The fallback the plan itself names for Windows |
| `minikube start --cpus=4 --memory=6g` on a 16 GB laptop | 2 CPUs, 2 GiB on an 8 GB laptop | The machine has 8 GB; Ansible VM and Minikube are run one at a time |
| Scale 3 → 5 → 2 | 3 → 4 → 3 | See below |
| HPA target 60% CPU | 70% CPU | **[CONFIRM]** At a 250m request, 70% means about 175m average per pod before scaling, against 150m at 60%. The higher target scales later, the more cautious setting on a node where each extra pod is a memory risk |
| HPA reacting to load (`hey`) | Configured and reading metrics; no load test | `hey` not installed, and an HPA scale-up may not fit in 2 GiB |
| ConfigMap demo 0.70 → 0.60 | Prepared and checked, not performed | Pending; script ready |
| Rolling update of `risk-model` to v2 | `risk-model` runs v2 from the start; an image-only rollout (dev → `43a2986`) was done | `MODEL_VERSION` comes from the ConfigMap, so the image change did not change the model |
| Manifest names `01-configmap.yaml`, `10-risk-model.yaml`, `20-scan-api.yaml`, `30-hpa.yaml` (README) | `configmap.yaml`, `risk-model.yaml`, `scan-api.yaml`, `hpa.yaml` | Cosmetic. `kubectl apply -f k8s/` still applies the namespace first |
| `imagePullPolicy: IfNotPresent` with the SHA tag (risk table) | `Always` with the SHA tag | Works, but every pod start needs Docker Hub; a demo without internet would fail on any restart |
| `MODEL_TIMEOUT_MS` 800 (SPEC) | 3000 | 800 ms caused avoidable fallbacks on this machine |

**Why the cluster is so tight.** The Minikube node advertises 8 CPUs and about 3.7 GiB to the scheduler, because that is what the WSL2 VM has. Docker, however, caps the Minikube container at 2 CPUs and 2 GiB (evidence file 14). The scheduler therefore admits pods the container cannot actually hold. With 3 + 2 pods running, the memory requests on the node (including Kubernetes' own system pods) already total about 1.8 GiB, and `kubectl top node` showed about 1.6 GiB in use. Each additional `scan-api` pod requests 384Mi with a 512Mi limit, so a 5th pod, or an HPA scale-up towards 6, would push the node past 2 GiB. That is why the manual demo stops at 4. It is also the likely cause of the node-wide probe timeouts in 2.3.4. The fix on a bigger machine is the plan's `--cpus=4 --memory=6g`.

## 4. Results

Each figure's file is in the path given.

| Figure | File | Shows |
|---|---|---|
| D1 | `evidence/ansible/ansible_first_deploy_1.png` | `ansible-playbook ... -K`: facts gathered, apt cache `changed`, Java, user, group, directory `ok`, JAR check passed |
| D2 | `evidence/ansible/ansible_first_deploy_2.png` | Same run: JAR copy, unit, service `ok`; recap `ok=11 changed=1` (the apt cache refresh only) |
| D3 | `evidence/ansible/ansible_second_deploy_1.png` | Second run, first tasks, all `ok` |
| D4 | `evidence/ansible/ansible_second_deploy_2.png` | Second run recap: `ok=11 changed=0 failed=0` |
| D5 | `evidence/ansible/ansible_health_check.png` | `systemctl is-active phishlens` → `active`; `/actuator/health` → 200, `{"status":"UP"}` |
| D6 | `evidence/k8s/k8s_final_status.png` | `scan-api` 3/3 and `risk-model` 2/2; five pods Running; `risk-model` ClusterIP 8000, `scan-api` NodePort 8080:30080 |
| D7 | `evidence/k8s/k8s_images.png` | Both Deployments on the `43a2986` Docker Hub images, 2 and 3 ready |
| D8 | `evidence/k8s/k8s_hpa.png` | `describe hpa`: created 20:13:25 IST, CPU 3% (8m) / 70%, min 3, max 6, 3 current / 3 desired, `ScalingActive True` |

Captured command output in `evidence/k8s/text-20261105-205710/` (read-only, 5 November 20:57–20:59 IST):

| File | Supports |
|---|---|
| 01, 02, 03 | Deployments, ReplicaSets, pods, Services, HPA; Deployment details |
| 04 | `describe pod`: the three probes, limits, ConfigMap env source |
| 05, 06 | The ConfigMap, and the values inside a running container |
| 07, 08, 09 | Rollout histories and the revision → image map (v3 is revision 5; revision 6 is the undo) |
| 10, 11, 12 | HPA state; `kubectl top`; metrics-server running |
| 13 | Events: manual 3 → 4 → 3; the v3 surge pod in back-off and its removal after the undo; the probe-timeout episode |
| 14 | Minikube container limits (2 CPUs, 2 GiB) |
| 15, 16 | NodePort from inside the node: `/api/info` commit `43a2986`; `/api/scan` `source: model`, `v2`; borderline URL 0.64 |
| 17 | Host → node IP fails (Docker driver) |
| 18 | Host → `minikube service` tunnel → NodePort: `/api/info` and `/api/scan` (`source: model`, `v2`) |
| 19 | `kubectl diff`: the live cluster matches `k8s/` |

**Summary of what is shown and what is not:**

| Implemented and verified | Configured, not demonstrated | Not done |
|---|---|---|
| Ansible role; re-run with `changed=0`; service active and healthy | HPA scale-up under load | Captured first Ansible run on a clean host |
| Namespace, ConfigMap, both Deployments and Services, matching the manifests | ConfigMap threshold demo (script and borderline URL ready) | v1 → v2 rolling update of `risk-model` |
| Resource requests and limits per SPEC §1.4 | | Screenshots of the scaling and rollback demos (events captured instead) |
| Startup, readiness and liveness probes | | Scale to 5 (not possible in 2 GiB) |
| Commit-tagged Docker Hub images; `/api/info` shows `43a2986` | | |
| NodePort access (from the node, and from the host through `minikube service`); `source: model`, `v2` | | |
| Manual scaling 3 → 4 → 3 (event log) | | |
| metrics-server; HPA reading metrics | | |
| Broken v3 in back-off with 3 old pods kept; `rollout undo`; `rollout history` | | |

## 5. Conclusion

Both deployment paths work. The Ansible role installs and runs `scan-api` on a VM and is idempotent. The Kubernetes manifests run both services from the commit-tagged images, configure them from one ConfigMap, gate traffic with probes, expose `scan-api` through a NodePort, and connect it to the model through cluster DNS: a request through the NodePort is scored by `risk-model` v2. A bad release was contained and undone without losing the running version.

What did not go as planned, and what I would do differently:

- The laptop was the binding constraint. With 2 GiB for the whole cluster, the scaling demo stopped at 4 pods, the HPA could not safely be pushed, and the node stalled once on its own. On this hardware I would start with `scan-api`'s memory request lowered for the demo, or run the cluster on the 16 GB machine the plan assumed.
- The first Ansible run on the VM was not captured, so the "changes" screenshot only shows an apt cache refresh. I would capture both runs on a fresh VM, and I would mark the apt cache task `changed_when: false` so `changed=0` does not depend on how recently the cache was refreshed.
- The scaling and rollback demos were done before I started capturing evidence systematically; for those, the event log captured afterwards is the only record. `scripts/capture-evidence.sh` now saves the evidence as each step is done.
- The rollouts were done with plain `kubectl set image`, so the history has no change-cause. The rollback script now records one.
- The HPA, the ConfigMap demo and the v2 rollout are ready to run but not yet shown.

## 6. References

1. DevOps course assignment instructions (course code on the handout), Component 2.
2. Project repository: https://github.com/MUKUL-PRASAD-SIGH/PhishLens
3. Ansible documentation, roles and handlers: https://docs.ansible.com/ansible/latest/playbook_guide/playbooks_reuse_roles.html
4. Kubernetes documentation, Deployments and rolling updates: https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
5. Kubernetes documentation, liveness, readiness and startup probes: https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/
6. Kubernetes documentation, ConfigMaps: https://kubernetes.io/docs/concepts/configuration/configmap/
7. Kubernetes documentation, Horizontal Pod Autoscaling: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
8. Minikube documentation, accessing apps (NodePort and `minikube service`): https://minikube.sigs.k8s.io/docs/handbook/accessing/
9. Spring Boot reference, Kubernetes probes: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
