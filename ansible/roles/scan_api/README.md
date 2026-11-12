# Role: scan_api

Installs the PhishLens `scan-api` Spring Boot JAR on an Ubuntu host and runs it as a systemd
service. Used by `ansible/playbook.yml` against the `scan_servers` group in `ansible/inventory.ini`.

## What it does

1. Refreshes the apt cache (at most once an hour, `cache_valid_time: 3600`).
2. Installs `openjdk-17-jre-headless`.
3. Creates the `phishlens` system group and user (no login shell) and `/opt/phishlens`.
4. Checks on the controller that the JAR exists, then copies it to `/opt/phishlens/scan-api.jar`.
5. Renders `phishlens.service` from `templates/phishlens.service.j2`; the environment variables
   in the unit (`SERVER_PORT`, `MODEL_URL`, `RISK_THRESHOLD`, `MODEL_TIMEOUT_MS`) come from the
   role variables.
6. Enables and starts the service.

The JAR copy and the unit template notify the handler `Restart phishlens` (systemd restart with
`daemon_reload`), so the service restarts only when one of them actually changes.

## Variables (`defaults/main.yml`)

| Variable | Default | Meaning |
|---|---|---|
| `app_user`, `app_group` | `phishlens` | Service account |
| `app_dir` | `/opt/phishlens` | Install directory |
| `app_jar` | `scan-api.jar` | JAR name on the host |
| `jar_src` | `{{ playbook_dir }}/../scan-api/target/scan-api.jar` | JAR on the controller; build it first with `mvn -f scan-api/pom.xml package` |
| `app_port` | `8080` | `SERVER_PORT` |
| `model_url` | `http://localhost:8000` | `MODEL_URL` (no risk-model runs on the VM, so scan-api uses its fallback scoring) |
| `risk_threshold` | `0.70` | `RISK_THRESHOLD` |
| `model_timeout_ms` | `800` | `MODEL_TIMEOUT_MS` |
| `java_package` | `openjdk-17-jre-headless` | Java runtime package |
| `service_name` | `phishlens` | systemd unit name |

## Run

From WSL, in the repository root:

```bash
ansible all -i ansible/inventory.ini -m ping
ansible-playbook -i ansible/inventory.ini ansible/playbook.yml -K   # first run: changed > 0
ansible-playbook -i ansible/inventory.ini ansible/playbook.yml -K   # second run: changed=0
ansible scan_servers -i ansible/inventory.ini -m uri -a "url=http://127.0.0.1:8080/actuator/health return_content=yes"
```

## Idempotency note

`changed=0` holds for a re-run within an hour. After the apt cache is older than
`cache_valid_time` (3600 s), the "Update apt package index" task refreshes it and reports
`changed`, even though nothing about the deployment changed.
