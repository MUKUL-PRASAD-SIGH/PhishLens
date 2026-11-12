# Kubernetes text evidence, captured 2026-11-05 20:57–20:59 IST

Each file holds the command, when it ran, and its unedited output. Files 01–17 were written by
`scripts/capture-evidence.sh` (read-only); 18 and 19 were captured by hand the same way. The cluster was not
changed to produce any of them. Event lines (file 13) give ages relative to 20:57:16 IST; Kubernetes
keeps events for about an hour, so this file is the only record of the events listed below.

| File | Shows |
|---|---|
| 01-get-all.txt | Deployments, ReplicaSets, pods, Services and HPA (3 scan-api + 2 risk-model Running; scan-api NodePort 8080:30080) |
| 02/03-describe-deploy-*.txt | Both Deployments: images, resources, probes, `envFrom` ConfigMap, RollingUpdate strategy |
| 04-describe-pod-scan-api.txt | A scan-api pod: startup/liveness/readiness probes, limits, ConfigMap env source |
| 05-configmap.txt | `phishlens-config` (RISK_THRESHOLD 0.70, MODEL_TIMEOUT_MS 3000, MODEL_VERSION v2, ...) |
| 06-pod-env.txt | The values a running scan-api container actually sees |
| 07/08-rollout-history-*.txt | Rollout history of both Deployments |
| 09-replicaset-revisions.txt | Revision → image map: scan-api rev 5 = `v3-broken-c1670ef` (0 replicas), rev 6 = `43a2986` (the undo) |
| 10-hpa.txt | HPA min 3 / max 6 / 70% CPU, current metric, conditions |
| 11-top.txt, 12-metrics-server.txt | metrics-server running; node and pod CPU/memory |
| 13-events.txt | Manual scaling 3→4 (~45 min earlier) and 4→3 (~44 min); the v3 rollout: one surge pod, `BackOff restarting failed container`, scaled 1→0 after the undo; the probe-timeout episode (~15 min earlier) |
| 14-minikube-node-limits.txt | The Minikube container's Docker limits: 2 CPUs, 2 GiB |
| 15-nodeport-info-health.txt | `/api/info` (commit 43a2986) and `/actuator/health` through NodePort 30080, from inside the node |
| 16-nodeport-scan-*.txt | `/api/scan` through the NodePort: `source: model`, `modelVersion: v2`; borderline URL scores 0.64 |
| 17-host-to-nodeport.txt | The Windows host cannot reach the node IP directly (curl exit 28): Docker-driver limitation |
| 18-minikube-service-tunnel.txt | `minikube service scan-api --url` tunnel from the Windows host to the NodePort: `/api/info` and `/api/scan` (`source: model`, v2) |
| 19-kubectl-diff-live-vs-manifests.txt | `kubectl diff` exit 0 for every manifest: the live cluster matches `k8s/` |
