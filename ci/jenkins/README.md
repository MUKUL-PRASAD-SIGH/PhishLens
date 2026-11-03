# Jenkins for PhishLens

A Jenkins controller in Docker that runs the root [`Jenkinsfile`](../../Jenkinsfile). The image (`ci/jenkins/Dockerfile`) is the official `jenkins/jenkins:2.580.1-lts-jdk21` plus:

| Tool | Version | Source |
|---|---|---|
| Maven | 3.9.16 | Apache archive, SHA-512 checked during the build (Debian's package is 3.8.x, below SPEC's 3.9+) |
| Python | 3.12.14 with `venv` | Copied from the official `python:3.12.14-slim-trixie` image (SPEC 1.3 pins 3.12; Debian 13 itself only ships 3.13) |
| Docker CLI + compose and buildx plugins | 29.9.2 / 5.6.1 / 0.38.1 | Docker's apt repository, signing key fingerprint checked |

Jenkins runs builds on its own JDK 21. scan-api still compiles to Java 17 class files, because `pom.xml` sets `<java.version>17</java.version>` and Maven passes `--release 17`.

## Build and run

Run these from the repository root. Docker Desktop must be running.

```bash
docker build -t phishlens-jenkins:2.580.1 ci/jenkins

docker volume create jenkins_home

docker run -d --name phishlens-jenkins --restart unless-stopped \
  -p 8081:8080 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --group-add "$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock --entrypoint stat phishlens-jenkins:2.580.1 -c %g /var/run/docker.sock)" \
  phishlens-jenkins:2.580.1
```

- **Port 8081.** Jenkins listens on 8080 inside the container, but 8080 on the laptop is taken by scan-api when it runs locally, so the UI is at <http://localhost:8081>.
- **`jenkins_home` volume.** Jobs, plugins, build history and the Maven cache (`~/.m2`) survive container restarts and image rebuilds. `docker rm -f phishlens-jenkins` doesn't delete it; `docker volume rm jenkins_home` does.
- **`/var/run/docker.sock`.** The Docker CLI inside Jenkins talks to the laptop's Docker daemon, so images built by Jenkins appear in the host's `docker images`. On Docker Desktop this path refers to the daemon inside Docker Desktop's VM, even though the Mac's own socket lives at `~/.docker/run/docker.sock`.
- **`--group-add`.** See [Docker socket permissions](#docker-socket-permissions).

## First login

```bash
docker exec phishlens-jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Open <http://localhost:8081>, paste the password, then:

1. Choose **Install suggested plugins**. This includes **Pipeline**, **Git**, **Credentials Binding**, **JUnit** and **Timestamper** (needed by `timestamps()` in the Jenkinsfile).
2. Create the admin user.
3. Go to **Manage Jenkins → Plugins → Available plugins**, install **Docker Pipeline** and restart.

Then create the job: [JOB_SETUP.md](JOB_SETUP.md).

## buildx

The Jenkinsfile builds images with `docker buildx build`, so the image needs the buildx plugin; rebuild and recreate the container after pulling a change to this Dockerfile (`jenkins_home` survives). The default builder (the host's `default` instance, through the mounted socket) is enough; no builder instance has to be created.

`DOCKER_PLATFORMS` in the Jenkinsfile lists the target platforms, `linux/amd64,linux/arm64` by default (M3's Minikube node is amd64). The pipeline uses it in two ways:

- **Build and scan: first platform only, `--load`.** The stage builds just the first entry (`linux/amd64`) into the local daemon, and Trivy scans that image with `--platform` set to the same value. Nothing is published before the scan passes. `--load` cannot hold a multi-platform image, which is why only one platform is built here.
- **Push, several platforms: `--push`.** The push stage runs `docker buildx build --platform linux/amd64,linux/arm64 --provenance=false --push`, so buildx sends a manifest list straight to Docker Hub. The layers come from the build cache. `--provenance=false` keeps the manifest list to exactly the two platforms; otherwise buildx adds `unknown/unknown` attestation entries.
- **Push, one platform: `docker push`.** With a single entry in `DOCKER_PLATFORMS` the loaded image is pushed as it is, and the same single-platform `--load` build works for local testing.

Notes from checking this:

- `--load` needs Docker's containerd image store (the Docker Desktop default). On a classic-store daemon, create a builder with `docker buildx create --use` for the multi-platform push.
- The `scan-api` jar is architecture-independent, so `scan-api/Dockerfile` pins its Maven stage to `$BUILDPLATFORM`. Only the small JRE runtime stage is built for a foreign platform, which runs briefly under QEMU. `risk-model`'s `pip install` does run under QEMU on an Apple Silicon laptop when it builds the amd64 image.
- To try the push path without touching Docker Hub, run `docker run -d -p 5055:5000 registry:2` (port 5000 is taken by macOS AirPlay) and push to `localhost:5055/<name>`, then check `docker buildx imagetools inspect`.

## Docker socket permissions

Jenkins runs as `jenkins` (uid 1000, gid 1000). On this laptop (Docker Desktop on Apple Silicon), the mounted socket looks like this from inside the container:

```
$ docker exec phishlens-jenkins stat -c '%U:%G (%u:%g) %A' /var/run/docker.sock
root:root (0:0) srw-rw----
```

On the Mac the socket is `~/.docker/run/docker.sock`, owned by `nikita:staff`. Docker Desktop doesn't pass those ids into the container: there the socket is owned by root, group 0, mode 660. So without a fix:

```
$ docker exec phishlens-jenkins id
uid=1000(jenkins) gid=1000(jenkins) groups=1000(jenkins)
$ docker exec phishlens-jenkins docker ps
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

**Fix used:** `--group-add` with the socket's group id as the container sees it. The `docker run` above looks the id up with a throwaway container from the same image, which prints `0` on Docker Desktop. `jenkins` then gets group 0 as a supplementary group:

```
$ docker exec phishlens-jenkins id
uid=1000(jenkins) gid=1000(jenkins) groups=1000(jenkins),0(root)
$ docker exec phishlens-jenkins docker ps
CONTAINER ID   IMAGE                       ...   PORTS                                         NAMES
873129f83d8b   phishlens-jenkins:2.580.1   ...   0.0.0.0:8081->8080/tcp, [::]:8081->8080/tcp   phishlens-jenkins
```

Jenkins still runs as uid 1000, and nothing is `chmod`ed on the socket or baked into the image. The cost is that, inside this container, `jenkins` can also read files owned by group 0, which is acceptable for a local CI box but not for a shared one. On a Linux host the lookup returns the host's `docker` group id instead of 0, and the same command works unchanged.

If you store the id in a variable in zsh first, don't call it `GID`: that name is special in zsh, and assigning to it fails with `failed to change group ID: operation not permitted`.

## Running scan-api locally next to Jenkins

JDK 17 from Homebrew isn't the system default on the Mac, so local runs need the JDK 17 `java` explicitly:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
$JAVA_HOME/bin/java -jar scan-api/target/scan-api.jar
```

The Jenkins image ships its own JDK and Maven, so it is unaffected.

## Stop, start, rebuild

```bash
docker stop phishlens-jenkins      # free memory before starting Minikube (SPEC 1.1)
docker start phishlens-jenkins
docker rm -f phishlens-jenkins && docker build -t phishlens-jenkins:2.580.1 ci/jenkins   # then the docker run above
```
