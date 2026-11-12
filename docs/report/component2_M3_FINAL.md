# Component 2: Configuration Management and Container Orchestration (Deployment)

**Project:** PhishLens, a malicious-URL risk scanner
**Component:** 2 of 2 (Ansible, Kubernetes, scaling, probes, ConfigMap, rollback and HPA). The Docker, multi-stage build, model and metrics sections are M2's and are not repeated here.
**Author:** M3 (Infrastructure and Deployment)
**Repository:** https://github.com/MUKUL-PRASAD-SIGH/PhishLens (also in `repo_link.txt`)

> **OPEN ITEMS BEFORE THIS REPORT IS FINAL (placeholders, to be resolved by the team):**
> 1. **Course code: NOT CONFIRMED.** Same open item as the Component 1 report. Placeholder: **[COURSE CODE]**.
> 2. **Title page and certificate: NOT INCLUDED.** Placeholder: **[TITLE PAGE AND CERTIFICATE: insert from template]**.
> 3. **M2's sections (Docker, model, metrics): NOT INCLUDED.** Placeholder: **[M2 SECTIONS]**. Figure numbers here start at D1 so they can be renumbered when the sections are merged.
>
> Evidence paths below are relative to the repository root. Existing screenshots are in `evidence/ansible/` and `evidence/k8s/`. Captured command output, each file holding the exact command, the time it ran and its unedited output, is in `evidence/k8s/text-20261105-205710/` (T01–T19), `evidence/k8s/final-20261105/` (F20–F23), `evidence/k8s/final-20261106/` (F24), `evidence/k8s/final-20261112/` (F21b, F25, F26) and `evidence/ansible/final-20261105/` (A00). `evidence/EVIDENCE_INDEX.md` lists every item. Scaling PNG screenshots are not included because the automated CLI runs in a non-GUI session; the exact commands to capture the genuine 3→4→3 screenshots manually are in §4.4, while full live text transcripts are in F21 (`demo-scale.sh`) and F21b (`demo-scale.ps1`).

---

## Abstract

This part of Component 2 deploys PhishLens in two ways. An Ansible role installs Java 17 on an Ubuntu Server VM, copies the `scan-api` JAR and runs it as a systemd service; a second run of the playbook reports `changed=0`. On a single-node Minikube cluster, both services run from the images the Jenkins pipeline pushed to Docker Hub with the commit tag `43a2986`. They are configured from one ConfigMap, health-checked by startup, readiness and liveness probes, and connected through cluster DNS, with `scan-api` exposed on a NodePort. A request through the NodePort is scored by `risk-model` v2 (`"source":"model"`). I scaled `scan-api` by hand from 3 to 4 and back. I changed the risk threshold in the ConfigMap, which turned the same URL from SUSPICIOUS into MALICIOUS without a code change. A load test made the HorizontalPodAutoscaler scale `scan-api` from 3 to 4 replicas on its own. A deliberately broken v3 release was rolled back with `kubectl rollout undo` while the three healthy pods kept serving. The laptop (8 GB of RAM; Minikube capped at 2 CPUs and 2 GiB) shaped the results. The manual scaling stops at 4 pods rather than 5, and the HPA test was capped at 4 replicas. When the cap was lifted, the HPA went to 6 and the node collapsed, which is reported in 2.3.7. Two items were not demonstrated: a v1 → v2 rolling update of `risk-model`, because no v1 image exists, and an Ansible run on a clean host. The results are shown in Figures D1–D8 and in the captured outputs.

## 1. Introduction

The assignment asks for configuration management with Ansible and orchestration with Kubernetes: deploy the application, show that the configuration is repeatable, scale it, configure it without rebuilding it, and recover from a bad release. This part does that for both PhishLens services:

- `scan-api` (Java 17, Spring Boot, written by M1) is the public API. `POST /api/scan` takes a URL and returns a score and a verdict: SAFE below 0.40, SUSPICIOUS from 0.40 up to a threshold that defaults to 0.70, MALICIOUS at or above it. `GET /api/info` returns the service name, version and the commit the image was built from.
- `risk-model` (Python, FastAPI, written by M2) scores the URL. If it is slow or down, `scan-api` falls back to local rules and says so (`"source":"fallback"`).

The project is shared by three people. This report covers my part: the `ansible/`, `k8s/` and `scripts/` folders and the deployment demos. I did not build either image; the Jenkins pipeline (Component 1) builds, scans and pushes them, and I deploy the tags it produced. Where my work depends on the others' (the images, the probe paths, the broken v3 image), I say so.

## 2. Implementation

### 2.1 Environment

| Item | Version / detail |
|---|---|
| Machine | Windows 11 Pro laptop, 8 GB RAM |
| Linux tooling | WSL2, Ubuntu 24.04: Git, Ansible, shell checks |
| Ansible | ansible-core 2.21.6, in the virtual environment `~/.venvs/ansible221` (Figure A00) |
| Ansible target | Ubuntu Server 26.10 (GNU/Linux 7.3.0-10-generic x86_64) on VirtualBox 7.2.22 (2 vCPUs, 2 GB), bridged network, `192.168.0.109`, SSH key login as `ubuntu`, Python `/usr/bin/python3.14` |
| Containers | Docker Desktop 29.9.2, WSL2 backend |
| Kubernetes | Minikube v1.39.0, Docker driver, Kubernetes v1.37.0, containerd 2.3.4; kubectl v1.36.1 from PowerShell |
| Minikube limits | 2 CPUs and 2 GiB, set on the Minikube container by Docker (T14) |
| Add-ons | `metrics-server` v0.9.0 |
| Images | `nikitaravindra/scan-api:43a2986`, `nikitaravindra/risk-model:43a2986` (Jenkins build #8); `nikitaravindra/scan-api:v3-broken-c1670ef` for the rollback demo |
| Load generator | `scripts/loadgen.py` (Python 3.12 standard library); `hey` is not installed |

The team plan assumed a 16 GB demo machine running `minikube start --cpus=4 --memory=6g` and a Multipass VM. On this 8 GB laptop Minikube gets 2 CPUs and 2 GiB, the Ansible VM uses VirtualBox (the fallback the plan names for Windows), and the two are not run at the same time.

### 2.2 Code

The files that belong to this part:

| Path | Purpose |
|---|---|
| `ansible/inventory.ini` | One host, `ubuntu-vm`, in group `scan_servers` |
| `ansible/playbook.yml` | Applies the `scan_api` role with `become: true` |
| `ansible/roles/scan_api/defaults/main.yml` | Variables: user, directory, JAR source, port, `MODEL_URL`, `RISK_THRESHOLD`, `MODEL_TIMEOUT_MS`, Java package |
| `ansible/roles/scan_api/tasks/main.yml` | apt cache, Java 17, group and user, `/opt/phishlens`, JAR check, JAR copy, systemd unit, enable and start |
| `ansible/roles/scan_api/handlers/main.yml` | `Restart phishlens`, with `daemon_reload` |
| `ansible/roles/scan_api/templates/phishlens.service.j2` | The systemd unit; configuration passed as `Environment=` lines |
| `k8s/00-namespace.yaml` | Namespace `phishlens` |
| `k8s/configmap.yaml` | ConfigMap `phishlens-config` |
| `k8s/risk-model.yaml` | Deployment (2 replicas) and ClusterIP Service on port 8000 |
| `k8s/scan-api.yaml` | Deployment (3 replicas) and NodePort Service, 8080 → node port 30080 |
| `k8s/hpa.yaml` | HorizontalPodAutoscaler for `scan-api`: min 3, max 6, 70% CPU |
| `scripts/demo-scale.sh`, `demo-scale.ps1` | Manual scaling; refuses to run while the HPA exists unless told to suspend it, and re-applies it afterwards |
| `scripts/demo-configmap.sh` | Threshold demo; backs up the ConfigMap and always restores the original value |
| `scripts/load-test.sh`, `scripts/loadgen.py` | HPA load test; samples the HPA every 15 s, stops if pods go `Pending`, reports a scale-up only if the replica count rose |
| `scripts/demo-rollback.sh` | Rollback demo with pre-flight checks; undoes the rollout itself if interrupted |
| `scripts/capture-evidence.sh` | Read-only capture of the cluster state used as evidence |

### 2.3 Description

#### 2.3.1 Ansible

The playbook runs one role, `scan_api`, against the VM. The role installs `openjdk-17-jre-headless`, creates a `phishlens` system user with no login shell, creates `/opt/phishlens`, and copies the JAR there. Before copying, a task on the controller checks that the JAR exists and stops with a clear message if it does not. The service is a systemd unit rendered from a template: `ExecStart` runs `java -XX:MaxRAMPercentage=75 -jar /opt/phishlens/scan-api.jar`, and the configuration (`SERVER_PORT`, `MODEL_URL`, `RISK_THRESHOLD`, `MODEL_TIMEOUT_MS`) comes from role variables as `Environment=` lines. The same JAR can therefore be reconfigured without rebuilding it. `Restart=on-failure` brings it back if it crashes.

Idempotency comes from the modules, not from scripts. `apt`, `group`, `user`, `file`, `copy` and `template` each compare the desired state with the host and change nothing if they already match. The JAR copy and the unit template notify the `Restart phishlens` handler, so the service restarts only when one of them actually changed.

The JAR on the VM is the early `scan-api` skeleton, as the team plan specifies for this step. It answers `/actuator/health` but has no model client, and no `risk-model` runs on the VM; the full service runs on Kubernetes (2.3.2).

**Runs (Figures D1–D5).** The run in Figures D1–D2 ends `ok=11 changed=1`. The one changed task is "Update apt package index"; the deployment tasks report `ok`, because Java, the user, the JAR and the service were already in place from an earlier run that was not captured. The next run ends `ok=11 changed=0` (Figures D3–D4). Figure D5 shows `systemctl is-active phishlens` returning `active` and `/actuator/health` returning HTTP 200 with `{"status":"UP"}`. The syntax check, task list and inventory graph pass with ansible-core 2.21.6 (Figure A00).

So the figures show idempotency, but not a first deployment onto a clean host. There is also a caveat on `changed=0`: the apt task uses `cache_valid_time: 3600`, so a run more than an hour after the last one refreshes the cache and reports one change, even when the deployment itself is unchanged. That is the change in Figure D2.

#### 2.3.2 Kubernetes architecture

```
 client ──▶ NodePort 30080 ──▶ Service scan-api (8080) ──▶ 3 × scan-api pods
                                                              │  MODEL_URL=http://risk-model:8000
                                                              ▼  (cluster DNS)
                                    Service risk-model (ClusterIP 8000) ──▶ 2 × risk-model pods
 ConfigMap phishlens-config ──envFrom──▶ both Deployments
 HPA scan-api-hpa ──▶ Deployment scan-api (3–6 replicas, 70% CPU)
```

Everything is in the namespace `phishlens`. `risk-model` is internal (ClusterIP); `scan-api` is the only entry point (NodePort 30080). Each Deployment selects its pods by an `app` label, and each Service targets the named container port `http`, so a port is changed in one place. The live cluster matched the files in `k8s/` (`kubectl diff` reported no differences; T19).

**Resources.** Requests and limits follow the budget in SPEC §1.4 exactly (T02–T04):

| Workload | Replicas | CPU request / limit | Memory request / limit |
|---|---|---|---|
| `scan-api` | 3 | 250m / 500m | 384Mi / 512Mi |
| `risk-model` | 2 | 100m / 250m | 128Mi / 256Mi |

`scan-api` also sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, so the JVM sizes its heap from the 512Mi container limit rather than the node's memory.

**Images.** Both Deployments use the commit-tagged images from Jenkins build #8, never `:latest` (Figure D7). The Component 1 enhancement carries through here: `GET /api/info` through the NodePort returns `"commit":"43a2986"` (T15, T18). That is the same value as the Jenkins build name, the archived JAR and the Docker Hub tag in the Component 1 report, so each running pod can be traced to its commit. The first manifests used locally built `phishlens/*:dev` images (rollout revisions 1–2); I switched to the Docker Hub images on 5 November (revision 3 of each Deployment; T09). `imagePullPolicy` is `Always`. That is safe with immutable SHA tags, but every pod start contacts Docker Hub (section 3).

#### 2.3.3 ConfigMap

Both Deployments load every key of `phishlens-config` as environment variables (`envFrom`), so no value is baked into an image (T05, T06):

| Key | Value | Used by |
|---|---|---|
| `SERVER_PORT` | `8080` | scan-api |
| `MODEL_URL` | `http://risk-model:8000` | scan-api: the ClusterIP Service name, resolved by cluster DNS |
| `RISK_THRESHOLD` | `0.70` | scan-api: score at or above this is MALICIOUS |
| `MODEL_TIMEOUT_MS` | `3000` | scan-api: budget before falling back (300 ms connect + the rest read) |
| `PORT` | `8000` | risk-model |
| `MODEL_PATH` | `/app/model/model.pkl` | risk-model |
| `MODEL_VERSION` | `v2` | risk-model: trained model instead of the v1 heuristic |

Two values differ from the SPEC defaults. `MODEL_TIMEOUT_MS` is 3000, not 800: with 800 ms, requests that `risk-model` could have answered were falling back to the local rules. And `APP_VERSION`/`APP_COMMIT` are deliberately absent. An earlier version set `APP_COMMIT: "local"`, which would override the commit baked into the image, so `/api/info` would report `local` instead of `43a2986`.

**Changing the configuration live (F20b).** Environment variables are read when a container starts, so a ConfigMap change needs `kubectl rollout restart`. The verdict rule makes the effect easy to see. A URL that the model scores between 0.60 and 0.70 is SUSPICIOUS at the default threshold and becomes MALICIOUS at 0.60. I found one by scanning candidates through the NodePort: `http://shop.example.net/item?id=123456789` scores 0.64 with `source: model`, and gave the same score on every scan (T16). `scripts/demo-configmap.sh` ran the demo on 5 November:

| Time (IST) | Step | Result |
|---|---|---|
| 21:33:05 | Before: all three pods see `RISK_THRESHOLD=0.70` | score 0.64, **SUSPICIOUS**, `source: model`, `v2` |
| 21:33:19 | ConfigMap backed up, then patched to 0.60 | pods still see 0.70 until they restart |
| 21:33:25 | `kubectl rollout restart deployment/scan-api` | rolled out; all three pods see 0.60 |
| 21:41:55 | Same URL, same image | score 0.64, **MALICIOUS**, `source: model`, `v2` |
| 21:42:06 | Restore 0.70 and restart | rolled out; all three pods see 0.70 |

The first attempt, 14 minutes earlier, did not get that far (F20a). Its restart was still waiting for the last old pod to terminate when the script's 300 s timeout expired, so the script stopped and restored 0.70, as it is designed to. On this node each new `scan-api` pod needs about two minutes to pass its startup probe, so restarting three pods took about eight minutes. The timeout is now 900 s, and the script checks the threshold on every pod instead of one.

#### 2.3.4 Health probes and resource management

| | Startup | Readiness | Liveness |
|---|---|---|---|
| `scan-api` | `/actuator/health`, every 5 s, 60 failures allowed (5 min) | `/actuator/health/readiness`, every 10 s, 3 failures | `/actuator/health/liveness`, every 20 s, 3 failures |
| `risk-model` | `/health`, every 5 s, 60 failures | `/health`, every 10 s, 3 failures | `/health`, every 20 s, 3 failures |

All probes have a 3 s timeout (T04). The startup probe holds the other two back until the JVM has booted, so a slow start is not mistaken for a hung process; on this node that matters, because a JVM takes about two minutes to start. Readiness decides whether a pod receives traffic; liveness decides whether the kubelet restarts it. The paths come from M1's `application.yml`. Readiness checks only the application's own state, not `risk-model`, by design: with the model down, `scan-api` still answers from its fallback rules, so a model outage must not take the API out of service. `risk-model`'s `/health` returns 503 if the model file did not load, so such a pod never becomes ready.

The probes were exercised by real events. On 5 November at about 20:42 IST every pod's probes timed out at the same moment, and the liveness probe restarted one `scan-api` pod, which rejoined on its own (T04, T13). Because all five pods and metrics-server were affected together, this looks like the node stalling under memory pressure (section 3), not a fault in either service. The same happened on a larger scale after the HPA test (2.3.7).

#### 2.3.5 Integration and NodePort access

`scan-api` reaches `risk-model` by the Service name in `MODEL_URL`, so cluster DNS and the ClusterIP Service spread its calls over both model pods. A scan through the NodePort that returns `"source":"model"` and `"modelVersion":"v2"` proves the whole path: client → NodePort → `scan-api` pod → DNS → `risk-model` Service → model pod. It was first checked with `kubectl port-forward`, which was not captured, and then through the NodePort:

1. **From the Windows host, through `minikube service scan-api -n phishlens --url`** (the method in the team plan's integration runbook). Minikube prints a local URL (`http://127.0.0.1:58536` in this run) and tunnels it to the NodePort. `/api/info` returned commit `43a2986`, and `/api/scan` with a suspicious URL returned score 1.0, MALICIOUS, `source: model`, `v2` (T18).
2. **From inside the Minikube node** (`minikube ssh`, then curl the NodePort paths `/api/info`, `/actuator/health` and `/api/scan` at `http://192.168.49.2:30080`), with the same answers (T15, T16).

A direct request from Windows to `192.168.49.2:30080` times out (T17). With the Docker driver on Windows, the node's IP is not routable from the host, which is why the tunnel is needed.

#### 2.3.6 Manual scaling

`kubectl scale` followed by `kubectl rollout status` changes the replica count; `scripts/demo-scale.sh` does this for a list of counts and prints the pods after each step. On 5 November at about 20:12 IST I scaled `scan-api` from 3 to 4 and back to 3; the event log records both steps on ReplicaSet `scan-api-688b576665` (T13). This was done before the HPA was created (20:13:25 IST, Figure D8), because an HPA with `minReplicas: 3` overrides manual changes.

I repeated it with `scripts/demo-scale.sh --suspend-hpa 3 4 3` on 5 November at 21:46 (F21), and again with the Windows PowerShell version `.\scripts\demo-scale.ps1 -SuspendHpa` on 12 November at 08:15 (F21b). In both runs the script deleted the HPA, scaled to 4, waited until the 4th pod was Ready (94 s in F21; 32 s in F21b), scaled back to 3, and re-applied `k8s/hpa.yaml`. The plan asked for 3 → 5 → 2; 5 pods do not fit on this node (section 3).

#### 2.3.7 Horizontal Pod Autoscaler

`k8s/hpa.yaml` (autoscaling/v2) scales `scan-api` between 3 and 6 replicas on average CPU utilisation, with a 70% target. Utilisation is measured against the request, so 70% of 250m is about 175m per pod. `metrics-server` is enabled, and the HPA reads metrics (`ScalingActive True, ValidMetricFound`; Figure D8, T10).

**Load test (F22).** `hey` is not installed, so `scripts/load-test.sh` used `scripts/loadgen.py`, a standard-library Python generator. Six workers, each waiting for its answer before sending the next, sent `POST /api/scan` for 180 s through the `minikube service` tunnel; the tunnel goes through the Service, so the requests are spread over the pods. For the test I lowered the HPA's `maxReplicas` to 4, because a single scale-up step from 3 could otherwise add three pods that the node cannot hold.

| Time (IST) | CPU (of target) | Replicas | Note |
|---|---|---|---|
| 21:48:14 | 14% / 70% | 3 | load starts |
| 21:49:04 | 56% / 70% | 3 | metrics catch up (metrics-server samples about once a minute) |
| 21:50:10 | 137% / 70% | 3 → 4 | `SuccessfulRescale: New size: 4; reason: cpu resource utilization (percentage of request) above target` |
| 21:50:27 | 137% / 70% | 4 | new pod running |
| 21:51:18 | 99% / 70% | 4 | load ends at about 21:51:10 |
| 21:51:50 | 59% / 70% | 4 | below target; scale-down waits for the 5-minute stabilisation window |

The generator sent 3,341 requests in 180.5 s (18.5 per second), all answered with HTTP 200. The HPA therefore **did scale up on its own**, from 3 to 4 replicas. The 4-replica ceiling was my temporary cap, so the plan's maximum of 6 was not exercised under load.

**What happened next.** The script then restored `maxReplicas` to 6, and the HPA immediately went to 6 replicas, at 3% CPU. During the test it had computed a desired count of 6 (137% × 3 / 70%, rounded up), which the cap held at 4. The HPA keeps its recommendations for a 5-minute scale-down window, so once the ceiling was raised it applied the remembered 6. I put the cap back within about two minutes and the Deployment returned to 4. The two extra JVMs had already started, though, and the node stopped responding. API requests timed out, metrics disappeared, and probes failed on every pod for over half an hour. At 23:16 IST the Minikube container was killed (exit code 137, not reported as an out-of-memory kill). I restarted Minikube the next morning with the HPA removed, waited for the pods to become Ready and idle, and re-applied `k8s/hpa.yaml` (2.3.10).

`scripts/load-test.sh` now keeps the cap until the stabilisation window has passed after the load. If it is interrupted before then, it leaves the cap in place and prints the restore command.

#### 2.3.8 Rolling update of risk-model to v2 (not demonstrated)

The plan asked for a rolling update of `risk-model` from v1 to v2 with `kubectl set image`. That cannot be shown with the images that exist. The model version is selected by `MODEL_VERSION` in the ConfigMap, which has been `v2` in every committed version. Every `risk-model` image on Docker Hub (tags `43a2986`/`b8` to `14e63fc`/`b12`) was built on 5 November from commits that already contained the v2 model, which was added in the first `risk-model` commit, `907a520`, on 22 October (F23). There is no v1 image to roll from. The `risk-model` history does contain one real rolling image update: revision 2 (`phishlens/risk-model:dev`) → revision 3 (`nikitaravindra/risk-model:43a2986`). Both ran v2, so it is not reported as a v1 → v2 update (T08, T09).

#### 2.3.9 Rollback

M1 built a deliberately broken v3 from the unmerged branch `demo/v3-broken`: `scan-api` refuses to start unless `REQUIRED_RELEASE_TOKEN` is set, and the ConfigMap does not set it. The variable was intentionally not added.

On 5 November at 20:14 IST I set the `scan-api` image to `nikitaravindra/scan-api:v3-broken-c1670ef`. The Deployment uses the default RollingUpdate strategy. With 3 replicas, the 25% surge rounds up to 1 and the 25% unavailable rounds down to 0, so Kubernetes started one new pod and kept all three old ones. The new pod failed at startup and went into `CrashLoopBackOff`. The event log records the one surge pod, repeated pulls of the v3 image, a failed startup probe and `Back-off restarting failed container scan-api`; the old ReplicaSet `scan-api-688b576665` was never scaled down (T13).

At about 20:19 IST I ran `kubectl rollout undo deployment/scan-api -n phishlens`. The v3 ReplicaSet went from 1 to 0, and the Deployment returned to `nikitaravindra/scan-api:43a2986` with 3/3 ready (T13, Figures D6–D7). `kubectl rollout history` lists revisions 1, 2, 3, 5 and 6 (T07). There is no revision 4 because `rollout undo` re-uses the previous ReplicaSet and renumbers it: revision 4 (`688b576665`, `43a2986`) became revision 6, and revision 5 is v3 (T09). The `CHANGE-CAUSE` column is empty in T07, because no `kubernetes.io/change-cause` annotation was set. `scripts/demo-rollback.sh`, written afterwards, sets one, checks the healthy state and the image before starting, and undoes the rollout itself if interrupted. On 12 November at 08:13 IST I executed `bash scripts/demo-rollback.sh --yes` end-to-end against the live cluster (F25): it deployed `nikitaravindra/scan-api:v3-broken-c1670ef` (revision 11, recording `CHANGE-CAUSE` as `rollback demo: deploy broken v3 (nikitaravindra/scan-api:v3-broken-c1670ef)`), waited for the new surge pod (`scan-api-7897f6c7d6-f2lcx`) to enter `CrashLoopBackOff`, printed its previous container log (`APPLICATION FAILED TO START ... REQUIRED_RELEASE_TOKEN is not set`), proved that all 3 old pods remained Ready and answered `GET /api/info` (`HTTP 200`, commit `43a2986`), and ran `kubectl rollout undo` to restore revision 12 (`3/3` Ready).

#### 2.3.10 Final state

After the restart on 6 November (`evidence/k8s/final-20261106/`, F24) and again after the cluster recovery and live scaling/rollback demonstrations on 12 November at 08:16 IST (`evidence/k8s/final-20261112/`, F26), the cluster state was verified:

| Item | State |
|---|---|
| `scan-api` | 3/3 Ready, `nikitaravindra/scan-api:43a2986` |
| `risk-model` | 2/2 Ready, `nikitaravindra/risk-model:43a2986` |
| `RISK_THRESHOLD` | 0.70 in the ConfigMap and in the running pods |
| HPA | min 3, max 6, 70% target; 1–6% current at idle; 3 replicas |
| Manifests | `kubectl diff` reports no differences for any file in `k8s/` (`exit=0`) |
| Other workloads | none: no pods outside Running, only the two current ReplicaSets have replicas |
| NodePort | `/api/info` commit `43a2986`; `/api/scan` `source: model`, `v2` |

When `minikube start` resumes an existing cluster where the HPA is already present, the existing Pod objects carry `status.startTime` timestamps older than Kubernetes' 5-minute `--cpu-initialization-period`. During the simultaneous JVM cold start of all 3 `scan-api` pods, their startup CPU spike (96%–148% of the 250m request) is therefore treated as live load by the HPA, which scales `scan-api` from 3 to 6 replicas (`SuccessfulRescale: New size: 6`). Six concurrent Spring Boot JVMs on 2 vCPUs stretch JVM startup from ~90 s to ~275–303 s, causing kubelet to kill containers that cross the 300 s `startupProbe` budget (`periodSeconds: 5 × failureThreshold: 60`, Exit Code 143) until the surviving pods finish warming up, CPU drops to ~6%, and the HPA scales the Deployment back down from 6 to 3 replicas after its 5-minute stabilisation window. To avoid this cold-start surge when starting Minikube, suspend `scan-api-hpa` until the 3 `scan-api` pods are Ready and idle, then re-apply `k8s/hpa.yaml`.

## 3. Deviations from the plan, and why

| Planned | Actual | Reason / effect |
|---|---|---|
| Multipass VM | VirtualBox Ubuntu Server VM | The fallback the plan names for Windows |
| `minikube start --cpus=4 --memory=6g`, 16 GB laptop | 2 CPUs, 2 GiB on an 8 GB laptop | The machine has 8 GB; the VM and Minikube are run one at a time |
| Scale 3 → 5 → 2 | 3 → 4 → 3 | A 5th `scan-api` pod does not fit in 2 GiB |
| HPA target 60% CPU | 70% CPU | At a 250m request, 70% means about 175m per pod before scaling, against 150m at 60%. The higher target scales later, the more cautious setting on a node where each extra pod is a memory risk |
| HPA reacting to `hey` load | Scaled 3 → 4 under load from `loadgen.py`, with `maxReplicas` temporarily capped at 4 | `hey` not installed; the cap kept the scale-up within what the node can hold |
| Rolling update of `risk-model` to v2 | Not demonstrated | No v1 image exists (2.3.8) |
| Manifest names `01-configmap.yaml`, `10-risk-model.yaml`, `20-scan-api.yaml`, `30-hpa.yaml` | `configmap.yaml`, `risk-model.yaml`, `scan-api.yaml`, `hpa.yaml` | Cosmetic; `kubectl apply -f k8s/` still applies the namespace first |
| `imagePullPolicy: IfNotPresent` (risk table) | `Always`, with the SHA tag | Works, but a demo without internet would fail on any pod restart |
| `MODEL_TIMEOUT_MS` 800 (SPEC) | 3000 | 800 ms caused avoidable fallbacks on this machine |

**Why the cluster is so tight.** The Minikube node advertises 8 CPUs and about 3.7 GiB to the scheduler, because that is what the WSL2 VM has, but Docker caps the Minikube container at 2 CPUs and 2 GiB (T14). The scheduler therefore admits pods the container cannot hold. With 3 + 2 pods running, the node's memory requests, including Kubernetes' own system pods, already total about 1.8 GiB, and `kubectl top node` showed 1.6–1.8 GiB in use. Each extra `scan-api` pod requests 384Mi. Four fit, as the scaling demo and the HPA test showed; six did not, and took the node down (2.3.7). The plan's `--cpus=4 --memory=6g` on a 16 GB machine would remove this limit.

## 4. Results

Each figure's file is in the path given.

**4.1 Ansible**

| Figure | File | Shows |
|---|---|---|
| D1 | `evidence/ansible/ansible_first_deploy_1.png` | `ansible-playbook -i ansible/inventory.ini ansible/playbook.yml -K`: facts gathered, apt cache `changed`, Java, group, user, directory `ok`, JAR check passed |
| D2 | `evidence/ansible/ansible_first_deploy_2.png` | Same run: JAR copy, unit, service `ok`; recap `ok=11 changed=1` (the apt cache only) |
| D3 | `evidence/ansible/ansible_second_deploy_1.png` | Second run, first tasks all `ok` |
| D4 | `evidence/ansible/ansible_second_deploy_2.png` | Second run recap `ok=11 changed=0 failed=0` |
| D5 | `evidence/ansible/ansible_health_check.png` | `systemctl is-active phishlens` → `active`; `/actuator/health` → 200, `{"status":"UP"}` |
| A00 | `evidence/ansible/final-20261105/A00-static-validation.txt` | ansible-core 2.21.6; `--syntax-check` exit 0; the ten role tasks; inventory graph |

**4.2 Kubernetes deployment**

| Figure | File | Shows |
|---|---|---|
| D6 | `evidence/k8s/k8s_final_status.png` | `scan-api` 3/3 and `risk-model` 2/2; five pods Running; ClusterIP 8000 and NodePort 8080:30080 |
| D7 | `evidence/k8s/k8s_images.png` | Both Deployments on the `43a2986` images |
| D8 | `evidence/k8s/k8s_hpa.png` | `describe hpa` at idle: 3% / 70%, min 3, max 6, 3 current / 3 desired, `ScalingActive True` |
| T01–T06 | `evidence/k8s/text-20261105-205710/01-get-all.txt`, `02-describe-deploy-scan-api.txt`, `03-describe-deploy-risk-model.txt`, `04-describe-pod-scan-api.txt`, `05-configmap.txt`, `06-pod-env.txt` | Objects, Deployment and pod details (probes, limits, `envFrom`), ConfigMap, the pod's environment |
| T14 | `evidence/k8s/text-20261105-205710/14-minikube-node-limits.txt` | Minikube container limits: 2 CPUs, 2 GiB |
| T19 | `evidence/k8s/text-20261105-205710/19-kubectl-diff-live-vs-manifests.txt` | Live cluster matches `k8s/` |

**4.3 Integration and NodePort**

| Figure | File | Shows |
|---|---|---|
| T15, T16 | `evidence/k8s/text-20261105-205710/15-nodeport-info-health.txt`, `16-nodeport-scan-borderline.txt`, `16-nodeport-scan-suspicious.txt` | From inside the node: `/api/info` commit `43a2986`; `/actuator/health` `UP`; `/api/scan` `source: model`, `v2`; borderline URL scored 0.64 |
| T17 | `evidence/k8s/text-20261105-205710/17-host-to-nodeport.txt` | Windows host → node IP: timeout (curl exit 28) |
| T18 | `evidence/k8s/text-20261105-205710/18-minikube-service-tunnel.txt` | Windows host → `minikube service` tunnel: `/api/info` and `/api/scan` (`source: model`, `v2`, MALICIOUS 1.0) |

**4.4 Configuration, scaling and autoscaling**

| Figure | File | Shows |
|---|---|---|
| F20a | `evidence/k8s/final-20261105/20a-configmap-demo-attempt1-rollout-timeout.txt` | First threshold attempt: restart exceeded 300 s; 0.70 restored |
| F20b | `evidence/k8s/final-20261105/20b-configmap-demo.txt` | 0.64: SUSPICIOUS at 0.70 → MALICIOUS at 0.60; restored to 0.70 |
| T13 | `evidence/k8s/text-20261105-205710/13-events.txt` | Manual scaling 3 → 4 → 3 (20:12 IST) |
| F21 | `evidence/k8s/final-20261105/21-scaling-demo.txt` | Scripted 3 → 4 → 3 (`demo-scale.sh --suspend-hpa`) with the HPA suspended and re-applied |
| F21b | `evidence/k8s/final-20261112/21b-scaling-demo-ps1.txt` | PowerShell 3 → 4 → 3 (`.\scripts\demo-scale.ps1 -SuspendHpa`) with the HPA suspended and re-applied |
| F22 | `evidence/k8s/final-20261105/22-load-test-console.txt`, `evidence/k8s/final-20261105/22-load-test/before.txt`, `after.txt`, `generator-output.txt`, `hpa-samples.log` | HPA samples, `SuccessfulRescale New size: 4`, generator summary (3,341 requests, all 200) |

To capture the three terminal PNG screenshots for the 3 → 4 → 3 scaling sequence manually from an interactive Windows terminal:
```powershell
kubectl -n phishlens delete hpa scan-api-hpa
kubectl -n phishlens scale deployment scan-api --replicas=3
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide   # Screenshot 1: initial 3 ready scan-api pods
kubectl -n phishlens scale deployment scan-api --replicas=4
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide   # Screenshot 2: 4 ready scan-api pods
kubectl -n phishlens scale deployment scan-api --replicas=3
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide   # Screenshot 3: restored 3 ready scan-api pods
kubectl apply -f k8s/hpa.yaml
```

**4.5 Rollout and rollback**

| Figure | File | Shows |
|---|---|---|
| T07, T08 | `evidence/k8s/text-20261105-205710/07-rollout-history-scan-api.txt`, `08-rollout-history-risk-model.txt` | Rollout histories of both Deployments |
| T09 | `evidence/k8s/text-20261105-205710/09-replicaset-revisions.txt` | Revision → image: 5 = `v3-broken-c1670ef` (0 replicas), 6 = `43a2986` (the undo) |
| T13 | `evidence/k8s/text-20261105-205710/13-events.txt` | v3 surge pod in back-off, old ReplicaSet kept, v3 ReplicaSet 1 → 0 after the undo |
| F25 | `evidence/k8s/final-20261112/25-rollback-demo.txt` | Full live run of `scripts/demo-rollback.sh --yes`: revision 11 (`v3-broken-c1670ef`) with `CHANGE-CAUSE`, surge pod in `CrashLoopBackOff` (`REQUIRED_RELEASE_TOKEN` missing), 3 healthy pods serving `/api/info` HTTP 200, `rollout undo` to revision 12 (`43a2986`) |
| F26 | `evidence/k8s/final-20261112/01-get-all.txt` .. `19-diff-and-stray-check.txt` | Final cluster state capture after the 12 November recovery and demos (`kubectl diff` exit 0) |
| F23 | `evidence/k8s/final-20261105/23-dockerhub-risk-model-tags.txt` | All risk-model images post-date v2; no v1 image |

**Summary of what is shown and what is not:**

| Implemented and verified | Partially demonstrated | Not demonstrated |
|---|---|---|
| Ansible role; re-run `changed=0`; service active and healthy | HPA scale-up: 3 → 4 under load, with the ceiling capped at 4 | Ansible run on a clean host (only re-runs captured) |
| Namespace, ConfigMap, both Deployments and Services, matching the manifests | Manual scaling: 3 → 4 → 3, not the planned 3 → 5 → 2 | Rolling update of `risk-model` v1 → v2 (no v1 image) |
| Requests and limits per SPEC §1.4; startup, readiness and liveness probes | | Screenshots of the v3 failure and undo (event log instead) |
| Commit-tagged images; `/api/info` shows `43a2986` | | |
| NodePort through the `minikube service` tunnel and from the node; `source: model`, `v2` | | |
| ConfigMap threshold change: SUSPICIOUS → MALICIOUS, restored | | |
| Broken v3 contained; `rollout undo`; `rollout history` | | |

## 5. Conclusion

Both deployment paths work. The Ansible role installs and runs `scan-api` on a VM and is idempotent. The Kubernetes manifests run both services from the commit-tagged images, configure them from one ConfigMap, gate traffic with probes, expose `scan-api` through a NodePort and connect it to the model through cluster DNS. Changing one ConfigMap value changed the service's verdicts without a rebuild, the HPA added a pod under load, and a bad release was contained and undone without losing the running version.

What did not go as planned, and what I would do differently:

- The laptop was the binding constraint. With 2 GiB for the whole cluster, manual scaling stopped at 4 pods, each restart took minutes, and the node stalled twice. I did not reduce the deployment resource requests; the technically justified remedy is to run the cluster on the 16 GB machine the plan assumed.
- Lifting the HPA cap right after the load test sent the HPA to 6 replicas, which took the node down overnight. I had not allowed for the HPA remembering its uncapped recommendations; the script now waits out the stabilisation window before restoring.
- The first ConfigMap attempt timed out because I sized the rollout timeout for a faster machine. The second attempt, with 900 s, worked.
- The first Ansible run was not captured, so the "changes" screenshot only shows an apt cache refresh. I would capture both runs on a fresh VM, and mark the apt cache task `changed_when: false` so `changed=0` does not depend on how recently the cache was refreshed.
- The rollouts were done with plain `kubectl set image`, so the history has no change-cause; the rollback script now records one.
- No v1 `risk-model` image was ever published, so the v1 → v2 rolling update could not be shown. Pushing an image per model version would have made it possible.

## 6. References

1. DevOps course assignment instructions (course code on the handout), Component 2.
2. Project repository: https://github.com/MUKUL-PRASAD-SIGH/PhishLens
3. Ansible documentation, roles and handlers: https://docs.ansible.com/ansible/latest/playbook_guide/playbooks_reuse_roles.html
4. Kubernetes documentation, Deployments and rolling updates: https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
5. Kubernetes documentation, liveness, readiness and startup probes: https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/
6. Kubernetes documentation, ConfigMaps: https://kubernetes.io/docs/concepts/configuration/configmap/
7. Kubernetes documentation, Horizontal Pod Autoscaling (including the stabilisation window): https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
8. Minikube documentation, accessing apps (NodePort and `minikube service`): https://minikube.sigs.k8s.io/docs/handbook/accessing/
9. Spring Boot reference, Kubernetes probes: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
