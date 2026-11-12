# M3 audit and handoff (updated 12 November 2026)

Internal handoff for M3's (Infrastructure & Deployment) work against `docs/TEAM_PLAN.md` and M1's review. The submission text is `docs/report/component2_M3_FINAL.md`; `component2_M3_deployment.md` is the earlier draft, kept for reference. Evidence IDs (D, T, F, A) are listed in `evidence/EVIDENCE_INDEX.md`.

## A. Final M3 requirement audit

COMPLETE = implemented and verified · PARTIAL · MISSING · BLOCKED

| # | Requirement | Status | Evidence | Notes |
|---|---|---|---|---|
| 1 | Ansible VM and inventory | COMPLETE | D1–D5, A00 | VirtualBox Ubuntu Server 26.10 instead of Multipass |
| 2 | Ansible role (defaults, tasks, handler, template) | COMPLETE | A00 | Syntax check and task list pass on ansible-core 2.21.6 |
| 3–5 | Java 17, JAR deployment, systemd service | COMPLETE | D1–D5 | Skeleton JAR, as planned |
| 6 | First run reports changes | PARTIAL | D1–D2 | Only the apt cache changed; clean first install not captured (C1) |
| 7 | Second run `changed=0` | COMPLETE | D3–D4 | Holds within 1 h (apt `cache_valid_time`) |
| 8–13 | Namespace, ConfigMap, Deployments, Services, requests/limits | COMPLETE | T01–T06, T19, F26 | `kubectl diff` exit 0 across all 5 manifests |
| 14 | Startup, liveness, readiness probes | COMPLETE | T04, F26 | Verified live (`/actuator/health`, `/liveness`, `/readiness`) |
| 15 | Real Docker Hub images | COMPLETE | D7, T15, F26 | `nikitaravindra/*:43a2986` |
| 16 | scan-api ↔ risk-model | COMPLETE | T16, T18, F26 | `source: model`, `modelVersion: v2` |
| 17 | NodePort curl | COMPLETE | T18 (tunnel), T15/T16/F26 (node), T17 (direct fails) | |
| 18 | Manual scaling | PARTIAL | T13, F21, F21b | Demonstrated 3 → 4 → 3 in both Bash (F21) and PowerShell (F21b); 3 → 5 → 2 is unsafe on the 2-GiB node |
| 19 | ConfigMap live change | COMPLETE | F20b (F20a first attempt) | SUSPICIOUS → MALICIOUS at 0.60; restored to 0.70 |
| 20 | HPA | PARTIAL | D8, T10, F26 | 70% instead of 60% |
| 21 | metrics-server | COMPLETE | T12, F26 | Running (`1/1`) in `kube-system` |
| 22 | HPA under load | PARTIAL | F22 | Scaled 3 → 4 automatically with max capped at 4; lifting the cap took the node down (report 2.3.7) |
| 23 | risk-model v1 → v2 rolling update | BLOCKED | F23 | No v1 image exists on Docker Hub |
| 24–26 | Broken v3, rollback, history | COMPLETE | T07, T09, T13, F25 | Full live run repeated and captured in F25 (`25-rollback-demo.txt`) |
| 27 | `demo-scale.sh` / `.ps1` | COMPLETE | F21, F21b | Both `.sh` (`+x` in Git index) and `.ps1` verified live |
| 28 | `demo-rollback.sh` | COMPLETE | F25 | Full `--yes` live run verified (revision 11 → undo to revision 12, exit 0) |
| 29 | `load-test.sh` | COMPLETE | F22 | Fixed afterwards: cap lifted only after the stabilisation window |
| 30 | Evidence checklist | PARTIAL | `evidence/EVIDENCE_INDEX.md` | All text transcripts verified; missing: clean Ansible first-run PNG and manual scaling/v3 PNG screenshots (C1–C3) |
| 31 | Demo rehearsal | MISSING | — | Section H |

## B. Work completed in the final phases (5–12 November 2026)

| Work | Files | Result |
|---|---|---|
| ConfigMap demo, live | `scripts/demo-configmap.sh` (timeout 900 s; per-pod check) | F20a (timed out, restored), F20b (passed) |
| Scaling demo, live (Bash & PowerShell) | `scripts/demo-scale.sh` (`chmod +x`, timeout 600 s), `scripts/demo-scale.ps1` | F21 (Bash) and F21b (PowerShell) both passed |
| Load test, live | `scripts/load-test.sh`, `scripts/loadgen.py` | F22: HPA 3 → 4 (3,341 requests, all HTTP 200) |
| Rollback demo, live | `scripts/demo-rollback.sh` (undo timeout 900 s) | F25: full `--yes` live run passed (revision 11 `CrashLoopBackOff` on missing `REQUIRED_RELEASE_TOKEN`, 3 old pods serving HTTP 200, `rollout undo` to revision 12) |
| v1 image search | — | F23: none exists |
| Ansible static validation | — | A00 (re-verified 12 Nov on `ansible-core 2.21.6` in WSL `Ubuntu-24.04`) |
| Clean-target VM | VirtualBox clone `Ubuntu-Server-ansible-clean` (1 GB, 1 CPU, same MAC) | Created, never started; see C1 |
| Cluster recovery & diagnosis (12 Nov) | `evidence/k8s/final-20261112/` (F26) | Diagnosed `minikube start` HPA cold-start surge (`1/6 Ready`, 6 replicas, Exit Code 143 on `startupProbe` boundary); verified self-healing to `3/3` Ready (`scan-api`) and `2/2` Ready (`risk-model`), HPA `1–6%/70%` |
| Evidence index, final report | `evidence/EVIDENCE_INDEX.md`, `docs/report/component2_M3_FINAL.md` | Updated with Ubuntu 26.10 version, §4.4 screenshot commands, F21b, F25, F26 |

## C. Remaining manual actions

**C1. Clean Ansible first deployment** (host memory: stop Minikube first, `minikube stop`). The clone keeps the working VM untouched; keep `Ubuntu-Server` powered off so the clone gets `192.168.0.109`.
```powershell
& 'C:\Program Files\Oracle\VirtualBox\VBoxManage.exe' startvm Ubuntu-Server-ansible-clean --type headless
```
In WSL, from the repo root, with `source ~/.venvs/ansible221/bin/activate`:
```bash
ansible all -i ansible/inventory.ini -m ping
# clean the CLONE (removes what the role creates)
ansible all -i ansible/inventory.ini -b -K -m shell -a "systemctl disable --now phishlens; rm -f /etc/systemd/system/phishlens.service; systemctl daemon-reload; rm -rf /opt/phishlens; userdel phishlens; groupdel phishlens; apt-get purge -y openjdk-17-jre-headless; apt-get autoremove -y"
ansible-playbook -i ansible/inventory.ini ansible/playbook.yml -K    # screenshot: first run, tasks changed
ansible-playbook -i ansible/inventory.ini ansible/playbook.yml -K    # screenshot: changed=0
ansible scan_servers -i ansible/inventory.ini -m uri -a "url=http://127.0.0.1:8080/actuator/health return_content=yes"
```
Then `VBoxManage controlvm Ubuntu-Server-ansible-clean poweroff`, `minikube start`. Delete the clone when done: `VBoxManage unregistervm Ubuntu-Server-ansible-clean --delete`.

**C2. Missing scaling screenshots.** The cluster was unavailable during this audit because Docker Desktop was not running. With Docker Desktop and Minikube available, capture genuine screenshots in three separate terminal states:
```powershell
minikube start
kubectl -n phishlens wait --for=condition=available deployment/scan-api --timeout=600s
kubectl -n phishlens delete hpa scan-api-hpa
kubectl -n phishlens scale deployment scan-api --replicas=3
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide
# capture screenshot: initial 3 ready scan-api pods
kubectl -n phishlens scale deployment scan-api --replicas=4
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide
# capture screenshot: 4 ready scan-api pods
kubectl -n phishlens scale deployment scan-api --replicas=3
kubectl -n phishlens rollout status deployment/scan-api --timeout=600s
kubectl -n phishlens get pods -l app=scan-api -o wide
# capture screenshot: restored 3 ready scan-api pods
kubectl apply -f k8s/hpa.yaml
```
Do not label these screenshots as captured until the commands produce the shown live pod lists.

**C3. Optional PNG screenshots of the rollback** (Minikube up, HPA present, nothing else running): `bash scripts/demo-rollback.sh --yes` (the full live text transcript is already captured in `evidence/k8s/final-20261112/25-rollback-demo.txt`, F25).

**C4. Before merging:** move `k8s/rollback-demo.yaml` and `scan-api/Dockerfile` (both untracked, not to commit; `origin/main` already carries M2's `scan-api/Dockerfile`) out of the tree; then `git merge origin/main` (the branch merges cleanly; do not rebase, the M3 commits are pushed).

**C5. Remaining report placeholders:** course code (`[COURSE CODE]`), title page (`[TITLE PAGE AND CERTIFICATE]`), and M2's sections (`[M2 SECTIONS]`). (`[UBUNTU VERSION]` was resolved to `Ubuntu 26.10 (GNU/Linux 7.3.0-10-generic x86_64)`.)

## D. Remaining limitations

- 2-GiB Minikube: 5+ `scan-api` pods do not fit; restarts take about 8 minutes; the node stalled twice and was killed once (after the HPA went to 6).
- HPA's maximum of 6 was never reached safely; the test was capped at 4.
- When `minikube start` resumes an existing cluster where `scan-api-hpa` is active, existing Pod `status.startTime` values are older than the 5-minute `--cpu-initialization-period`, so the HPA reacts to JVM cold-start CPU (`96%–148%`) and scales `scan-api` from 3 to 6, stretching JVM startup past the 300 s `startupProbe` budget (Exit Code 143) until the surviving pods warm up and HPA scales back down to 3. Delete `scan-api-hpa` before `minikube start` or wait for the 5-minute stabilisation window.
- No v1 `risk-model` image; the rolling update cannot be shown without one.
- `imagePullPolicy: Always` needs internet on demo day; pre-pull the images or switch to `IfNotPresent`.

## E. Final cluster state

Checked 6 Nov 06:58 IST (`evidence/k8s/final-20261106/`, F24) and re-verified 12 Nov 08:17 IST (`evidence/k8s/final-20261112/`, F26): `scan-api` 3/3 and `risk-model` 2/2 on `43a2986`; `RISK_THRESHOLD` 0.70 in the ConfigMap and the pods; HPA min 3 / max 6 / 70%, 1–6% current at idle, 3 replicas; `kubectl diff` clean (`exit=0` for all 5 manifests); no stray pods or ReplicaSets.

## F. Teammate dependencies & M1 requirement status

| From | What | Status |
|---|---|---|
| M1 | `scan-api` service, metrics, probes, 95 JUnit tests, `Jenkinsfile`, `ci/`, `.github/workflows/ci.yml`, Component 1 screenshots (01–34) | Delivered and merged on `origin/main` (PRs #1–#20) |
| M1 | v3-broken image (`demo/v3-broken` branch, unmerged) | Delivered (`nikitaravindra/scan-api:v3-broken-c1670ef`), verified live in F25 |
| M2 | `risk-model` v1/v2, 61 pytest tests, Dockerfiles, `docker-compose.yml`, `docs/PROMETHEUS.md` | Delivered and merged on `origin/main` (PRs #3, #15, #21, #22) |
| M2 | v2 tag | Delivered in `43a2986`; no separate v1 image, so no v1 → v2 rollout |
| M2 | Docker / model / metrics report sections | Needed for the combined Component 2 report |
| M1 | Course code, title page template | Open (shared with Component 1) |

## G. Git summary

Branch `feat/m3-infrastructure` (`HEAD` at `346e3f7`, 2 ahead of `origin/main` base `43a2986`, 48 behind `origin/main` tip `14e63fc`). Pushed M3 commits `02aff20`, `346e3f7` untouched. Nothing committed or pushed during this audit.

- Staged (`chmod +x` + modified): `scripts/demo-scale.sh` (`100755` in Git index)
- Modified (unstaged): `ansible/roles/scan_api/README.md`, `ansible/roles/scan_api/meta/main.yml`, `scripts/demo-scale.ps1`
- New M3-owned (untracked): `scripts/.gitattributes`, `scripts/demo-configmap.sh`, `scripts/demo-rollback.sh`, `scripts/load-test.sh`, `scripts/loadgen.py`, `scripts/capture-evidence.sh`, `evidence/EVIDENCE_INDEX.md`, `evidence/ansible/final-20261105/`, `evidence/k8s/text-20261105-205710/`, `evidence/k8s/final-20261105/`, `evidence/k8s/final-20261106/`, `evidence/k8s/final-20261112/`, `docs/report/component2_M3_deployment.md`, `docs/report/component2_M3_FINAL.md`, `docs/report/M3_audit_and_handoff.md`
- Untracked, excluded from M3 commit: `scan-api/Dockerfile` (M2-owned; `origin/main` already carries the multi-stage `scan-api/Dockerfile`), `k8s/rollback-demo.yaml` (standalone scratch manifest not used by `scripts/demo-rollback.sh`)

## H. Demo sequence (about 5 minutes)

Before: stop Jenkins and the VM; `minikube start`; wait for 3/3 and 2/2; open `minikube service scan-api -n phishlens --url` in its own terminal.

1. Ansible run and re-run (`changed=0`), health check (WSL, VM on; then power the VM off).
2. `kubectl get pods,svc -n phishlens`; `curl.exe -s <tunnel>/api/info` (commit `43a2986`); `/api/scan` (`source: model`, `v2`).
3. `.\scripts\demo-scale.ps1 -SuspendHpa` (3 → 4 → 3).
4. `kubectl get hpa -n phishlens`; show F22 (load-test result) rather than re-running it live.
5. ConfigMap: show F20b (a live run takes about 16 minutes on this laptop).
6. `bash scripts/demo-rollback.sh --yes` (v3 in back-off, 3 pods serving, undo, history).
