# PhishLens Jenkins controller: Jenkins LTS (JDK 21) plus the tools the Jenkinsfile runs:
# Maven 3.9 for scan-api, Python 3.12 with venv for the risk-model tests, and the Docker CLI
# with the compose plugin, talking to the host daemon through the mounted docker.sock.
# Build and run instructions: ci/jenkins/README.md.

# Python 3.12 (SPEC 1.3) from the official image. Debian trixie itself ships only 3.13;
# this image is built on the same trixie release, so its /usr/local build runs unchanged.
FROM python:3.12.14-slim-trixie AS python

FROM jenkins/jenkins:2.580.1-lts-jdk21

# Maven from the Apache archive, not Debian's package (SPEC 1.3 asks for 3.9+).
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
# Docker's apt repository for Debian 13 (trixie), which this Jenkins image is based on.
ARG DOCKER_CLI_VERSION=5:29.9.2-1~debian.13~trixie
ARG COMPOSE_PLUGIN_VERSION=5.6.1-1~debian.13~trixie
ARG DOCKER_GPG_FINGERPRINT=9DC858229FC7DD38854AE2D88D81803C0EBFCD88

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && rm -rf /var/lib/apt/lists/*

COPY --from=python /usr/local/bin/ /usr/local/bin/
COPY --from=python /usr/local/lib/ /usr/local/lib/
COPY --from=python /usr/local/include/ /usr/local/include/
RUN ldconfig && python3.12 --version

RUN set -eux; \
    curl -fsSLo /tmp/maven.tar.gz \
        "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c -; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1; \
    rm /tmp/maven.tar.gz; \
    ln -s /opt/maven/bin/mvn /usr/local/bin/mvn
ENV MAVEN_HOME=/opt/maven

RUN set -eux; \
    install -m 0755 -d /etc/apt/keyrings; \
    curl -fsSLo /etc/apt/keyrings/docker.asc https://download.docker.com/linux/debian/gpg; \
    gpg --show-keys --with-colons /etc/apt/keyrings/docker.asc | grep -q "^fpr:::::::::${DOCKER_GPG_FINGERPRINT}:$"; \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        > /etc/apt/sources.list.d/docker.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        "docker-ce-cli=${DOCKER_CLI_VERSION}" "docker-compose-plugin=${COMPOSE_PLUGIN_VERSION}"; \
    rm -rf /var/lib/apt/lists/*

USER jenkins
