# Prometheus: how PhishLens would be monitored

PhishLens exposes Prometheus-format metrics from both services. We do not run a Prometheus server in the demo; this page shows how one would scrape them and what each metric means. Metric names come from [SPEC.md](SPEC.md) section 2.

## Endpoints

| Service | Path | Port | Library |
|---|---|---|---|
| `scan-api` | `/actuator/prometheus` | 8080 | Spring Boot Actuator + Micrometer |
| `risk-model` | `/metrics` | 8000 | `prometheus-fastapi-instrumentator` |

## Sample scrape config

Static targets (Docker Compose, or the NodePort on a VM):

```yaml
scrape_configs:
  - job_name: scan-api
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ["scan-api:8080"]

  - job_name: risk-model
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets: ["risk-model:8000"]
```

In Kubernetes, use pod discovery so every replica is scraped:

```yaml
scrape_configs:
  - job_name: phishlens-pods
    kubernetes_sd_configs:
      - role: pod
        namespaces: { names: [phishlens] }
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: scan-api|risk-model
        action: keep
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: scan-api
        target_label: __metrics_path__
        replacement: /actuator/prometheus
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: risk-model
        target_label: __metrics_path__
        replacement: /metrics
      - source_labels: [__meta_kubernetes_pod_ip, __meta_kubernetes_pod_label_app]
        regex: (.+);scan-api
        target_label: __address__
        replacement: $1:8080
      - source_labels: [__meta_kubernetes_pod_ip, __meta_kubernetes_pod_label_app]
        regex: (.+);risk-model
        target_label: __address__
        replacement: $1:8000
```

## What each metric tells you

| Metric | Service | Golden signal | Reading it |
|---|---|---|---|
| `http_server_requests_seconds_count` / `_sum` / `_max` (`uri`, `status`) | scan-api | Traffic, latency, errors | `rate(..._count[1m])` is requests per second; `_sum/_count` is mean latency; `status=~"5.."` is the error rate |
| `phishlens_scans_total{verdict}` | scan-api | Traffic | Scans per verdict. A sudden jump in `MALICIOUS` may be an attack wave or a threshold change |
| `phishlens_model_fallback_total` | scan-api | Errors | Times `risk-model` was unreachable or slow and local rules answered. It should stay near zero; a rising value means degraded accuracy |
| `phishlens_model_latency_seconds` | scan-api | Latency | Time spent calling `risk-model`. Compare with `MODEL_TIMEOUT_MS` (800 ms) |
| `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage` | scan-api | Saturation | Heap pressure and CPU against the 512Mi / 500m limits. CPU is what the HPA scales on |
| `http_requests_total`, `http_request_duration_seconds` | risk-model | Traffic, latency, errors | Same reading as the scan-api timer, for the model service |
| `phishlens_predictions_total{model_version}` | risk-model | Traffic | Predictions per model version. During the v1 to v2 rolling update the v2 share rises as pods are replaced |

## Example queries

```promql
sum(rate(http_server_requests_seconds_count{job="scan-api"}[1m]))                      # requests/s
sum(rate(http_server_requests_seconds_count{job="scan-api",status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{job="scan-api"}[5m]))                  # error ratio
rate(phishlens_model_fallback_total[5m])                                               # fallback rate
sum by (model_version) (rate(phishlens_predictions_total[5m]))                         # v1 vs v2 traffic
```

## Alert ideas

- Fallback rate above 0.1 per second for 5 minutes: `risk-model` is unhealthy or too slow.
- 5xx ratio above 5% for 5 minutes on `scan-api`.
- `process_cpu_usage` above 0.8 for 10 minutes: the HPA is at its limit.
