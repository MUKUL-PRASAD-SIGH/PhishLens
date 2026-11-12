# M3 evidence index (Ansible and Kubernetes)

All evidence comes from real runs on the demo laptop. Screenshots (D) are terminal captures; text files (T, F, A) hold the exact command, the time it ran and its unedited output. Status: **Verified** = captured and checked during the final audit; **Earlier** = captured before the audit and checked visually; **Missing**; **Not demonstrable** (environment constraint).

| Evidence ID | Filename (relative to repository root) | What it proves | Verification status |
|---|---|---|---|
| D1 | `evidence/ansible/ansible_first_deploy_1.png` | Playbook run: tasks reach the VM; apt cache `changed`, the rest `ok` | Earlier (not a clean first install: see D2) |
| D2 | `evidence/ansible/ansible_first_deploy_2.png` | Recap `ok=11 changed=1`; the one change is the apt cache refresh | Earlier |
| D3 | `evidence/ansible/ansible_second_deploy_1.png` | Second run, first tasks all `ok` | Earlier |
| D4 | `evidence/ansible/ansible_second_deploy_2.png` | Second run recap `ok=11 changed=0` (idempotency) | Earlier |
| D5 | `evidence/ansible/ansible_health_check.png` | `systemctl is-active phishlens` = `active`; `/actuator/health` 200 `UP` | Earlier |
| A00 | `evidence/ansible/final-20261105/A00-static-validation.txt` | ansible-core 2.21.6; `--syntax-check` exit 0; task list; inventory graph | Verified |
| — | (clean first deployment) | First run on a host without Java, user, JAR or service | Missing (clone VM prepared; not run: host memory) |
| D6 | `evidence/k8s/k8s_final_status.png` | Deployments 3/3 and 2/2; five pods Running; ClusterIP and NodePort Services | Earlier |
| D7 | `evidence/k8s/k8s_images.png` | Both Deployments on the `43a2986` Docker Hub images | Earlier |
| D8 | `evidence/k8s/k8s_hpa.png` | HPA min 3 / max 6 / 70%, metrics read (`ScalingActive True`) at idle | Earlier |
| T01 | `evidence/k8s/text-20261105-205710/01-get-all.txt` | Deployments, ReplicaSets, pods, Services, HPA | Verified |
| T02–T04 | `evidence/k8s/text-20261105-205710/02-describe-deploy-scan-api.txt`, `evidence/k8s/text-20261105-205710/03-describe-deploy-risk-model.txt`, `evidence/k8s/text-20261105-205710/04-describe-pod-scan-api.txt` | Probes (startup, readiness, liveness), requests/limits, `envFrom` ConfigMap | Verified |
| T05–T06 | `evidence/k8s/text-20261105-205710/05-configmap.txt`, `evidence/k8s/text-20261105-205710/06-pod-env.txt` | ConfigMap contents and the values a running pod sees | Verified |
| T07–T09 | `evidence/k8s/text-20261105-205710/07-rollout-history-scan-api.txt`, `evidence/k8s/text-20261105-205710/08-rollout-history-risk-model.txt`, `evidence/k8s/text-20261105-205710/09-replicaset-revisions.txt` | Rollout histories; revision 5 = `v3-broken-c1670ef`, revision 6 = the undo to `43a2986` | Verified |
| T10–T12 | `evidence/k8s/text-20261105-205710/10-hpa.txt`, `evidence/k8s/text-20261105-205710/11-top.txt`, `evidence/k8s/text-20261105-205710/12-metrics-server.txt` | HPA state, `kubectl top`, metrics-server running | Verified |
| T13 | `evidence/k8s/text-20261105-205710/13-events.txt` | Manual scaling 3→4→3 (20:12 IST); v3 surge pod in `BackOff`, old RS kept, v3 RS 1→0 after undo; probe-timeout episode | Verified |
| T14 | `evidence/k8s/text-20261105-205710/14-minikube-node-limits.txt` | Minikube container capped at 2 CPUs / 2 GiB | Verified |
| T15–T16 | `evidence/k8s/text-20261105-205710/15-nodeport-info-health.txt`, `evidence/k8s/text-20261105-205710/16-nodeport-scan-borderline.txt`, `evidence/k8s/text-20261105-205710/16-nodeport-scan-suspicious.txt` | NodePort from inside the node: `/api/info` commit `43a2986`; `/api/scan` `source: model`, `v2` | Verified |
| T17 | `evidence/k8s/text-20261105-205710/17-host-to-nodeport.txt` | Windows host → `192.168.49.2:30080` times out (Docker driver) | Verified |
| T18 | `evidence/k8s/text-20261105-205710/18-minikube-service-tunnel.txt` | Windows host → `minikube service --url` tunnel → NodePort: `/api/info` and `/api/scan` | Verified |
| T19 | `evidence/k8s/text-20261105-205710/19-kubectl-diff-live-vs-manifests.txt` | Live cluster matches `k8s/` (`kubectl diff` exit 0) | Verified |
| F20a | `evidence/k8s/final-20261105/20a-configmap-demo-attempt1-rollout-timeout.txt` | First ConfigMap attempt: restart exceeded the 300 s timeout; script restored 0.70 | Verified |
| F20b | `evidence/k8s/final-20261105/20b-configmap-demo.txt` | ConfigMap demo: same URL, score 0.64, SUSPICIOUS at 0.70 → MALICIOUS at 0.60; restored to 0.70 | Verified |
| F20c | `evidence/k8s/final-20261105/configmap-backup-20261105-211915.yaml`, `evidence/k8s/final-20261105/configmap-backup-20261105-213318.yaml` | ConfigMap backups taken before each change | Verified |
| F21 | `evidence/k8s/final-20261105/21-scaling-demo.txt` | `demo-scale.sh --suspend-hpa 3 4 3`: 3/3 → 4/4 → 3/3, HPA re-applied | Verified |
| F21b | `evidence/k8s/final-20261112/21b-scaling-demo-ps1.txt` | `.\scripts\demo-scale.ps1 -SuspendHpa`: PowerShell 3/3 → 4/4 → 3/3 (new pod Ready in 32s), HPA re-applied | Verified |
| F22 | `evidence/k8s/final-20261105/22-load-test-console.txt`, `evidence/k8s/final-20261105/22-load-test/` | Load test: CPU 14% → 56% → 137% of target; `SuccessfulRescale New size: 4`; 3,341 requests, all 200 | Verified (scale-up capped at 4) |
| F23 | `evidence/k8s/final-20261105/23-dockerhub-risk-model-tags.txt` | Every risk-model image on Docker Hub post-dates the v2 model; no v1 image exists | Verified |
| F24 | `evidence/k8s/final-20261106/` | Final state after the 6 Nov Minikube restart | Verified |
| F25 | `evidence/k8s/final-20261112/25-rollback-demo.txt` | `demo-rollback.sh --yes`: deploys `v3-broken-c1670ef` (rev 11, change-cause recorded), surge pod in `CrashLoopBackOff` (`REQUIRED_RELEASE_TOKEN` missing), 3 old pods serving `/api/info` HTTP 200, `rollout undo` to rev 12 (`43a2986`) | Verified |
| F26 | `evidence/k8s/final-20261112/01-get-all.txt` .. `evidence/k8s/final-20261112/19-diff-and-stray-check.txt` | Complete post-demo verification on 12 Nov (`scan-api` 3/3, `risk-model` 2/2, HPA 1%/70%, `kubectl diff` exit 0, NodePort `/api/scan` `source: model`, `v2`) | Verified |
| — | (risk-model v1 → v2 rolling update) | — | Not demonstrable (no v1 image retained) |
| — | Scaling screenshots (initial 3, scaled 4, restored 3) | Genuine terminal screenshots of the demonstrated 3→4→3 sequence | Missing (headless CLI cannot capture GUI screen; exact capture commands in report §4.4 and handoff §C2) |
| — | Screenshots of the v3 `CrashLoopBackOff` and `rollout undo` | Pod list during the failure | Missing (full live transcript in F25, event log T13, and revision map T09 instead) |

Paths are relative to the repository root (consistent with `docs/report/component2_M3_FINAL.md`).
