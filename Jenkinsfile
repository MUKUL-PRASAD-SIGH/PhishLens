// PhishLens CI: test, package and archive scan-api on every push to main.
// Job setup: ci/jenkins/JOB_SETUP.md. Controller image: ci/jenkins/README.md.
pipeline {
    agent any

    options {
        timestamps()
        // The Checkout stage below is the only checkout.
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    // Poll GitHub every minute. Jenkins registers this trigger only after the first
    // (manual) run has read this file.
    triggers {
        pollSCM('* * * * *')
    }

    environment {
        PYTEST_VERSION = '9.1.1'
        // Docker Hub namespace for the images. Lowercase on purpose: Docker rejects uppercase in
        // image names. If it is set back to 'change-me' the images are built but not pushed.
        // SPEC 3.4 says phishlens/, which isn't ours; the images live under this account instead.
        DOCKER_NAMESPACE = 'nikitaravindra'
        TRIVY_IMAGE = 'aquasec/trivy:0.69.3'
        // Comma-separated buildx platforms. The first one is built locally and scanned by Trivy, so
        // keep linux/amd64 (M3's Minikube) first. A single entry gives a single-platform build.
        DOCKER_PLATFORMS = 'linux/amd64,linux/arm64'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.SHORT_SHA}"
                }
            }
        }

        stage('Tests') {
            parallel {
                stage('Test') {
                    steps {
                        sh 'mvn -B -f scan-api/pom.xml test'
                    }
                    post {
                        always {
                            junit 'scan-api/target/surefire-reports/*.xml'
                        }
                    }
                }

                stage('Model tests') {
                    when {
                        expression { fileExists('risk-model/tests') }
                    }
                    steps {
                        sh '''
                            python3.12 -m venv .venv-model
                            .venv-model/bin/pip install --quiet --disable-pip-version-check \
                                -r risk-model/requirements.txt "pytest==${PYTEST_VERSION}"
                            .venv-model/bin/python -m pytest risk-model/tests --junitxml=risk-model/pytest-results.xml
                        '''
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: 'risk-model/pytest-results.xml'
                        }
                    }
                }
            }
        }

        stage('Package') {
            steps {
                sh 'mvn -B -f scan-api/pom.xml -DskipTests package'
            }
        }

        stage('Archive') {
            steps {
                // scan-api.jar is the canonical output (SPEC 3.4); the copy ties the artifact to its commit.
                sh 'cp scan-api/target/scan-api.jar "scan-api/target/scan-api-${SHORT_SHA}-b${BUILD_NUMBER}.jar"'
                archiveArtifacts artifacts: "scan-api/target/scan-api.jar, scan-api/target/scan-api-${env.SHORT_SHA}-b${env.BUILD_NUMBER}.jar",
                        fingerprint: true
            }
        }

        // Both images are tagged ${SHORT_SHA} and b${BUILD_NUMBER} (never :latest). The stages after the
        // checkout are skipped until scan-api/Dockerfile (M2) exists.
        // Build: only the first platform of DOCKER_PLATFORMS, loaded into the local daemon so Trivy can
        // scan it before anything is published. --load needs Docker's containerd image store (the
        // Docker Desktop default). The jar is built on the native platform (see scan-api/Dockerfile),
        // so a foreign-platform build only emulates the small runtime stage.
        stage('Docker build') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                sh '''
                    SCAN_PLATFORM="${DOCKER_PLATFORMS%%,*}"
                    docker buildx build --platform "$SCAN_PLATFORM" --load \
                        -f scan-api/Dockerfile --build-arg APP_COMMIT="$SHORT_SHA" \
                        -t "$DOCKER_NAMESPACE/scan-api:$SHORT_SHA" -t "$DOCKER_NAMESPACE/scan-api:b$BUILD_NUMBER" scan-api
                    docker buildx build --platform "$SCAN_PLATFORM" --load \
                        -f risk-model/Dockerfile \
                        -t "$DOCKER_NAMESPACE/risk-model:$SHORT_SHA" -t "$DOCKER_NAMESPACE/risk-model:b$BUILD_NUMBER" risk-model
                '''
            }
        }

        // Trivy runs as a container against the host daemon (the Jenkins image has no Trivy), on the
        // platform that was built above, and fails the build on fixable CRITICAL findings, apart from
        // the accepted, expiring entries in ci/trivy/.trivyignore.yaml.
        stage('Trivy scan') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                sh '''
                    SCAN_PLATFORM="${DOCKER_PLATFORMS%%,*}"
                    # The ignore file is copied in with docker cp: a bind mount of the workspace would
                    # name a path on the Docker host, not inside this Jenkins container.
                    for img in scan-api risk-model; do
                        cid=$(docker create -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache \
                            "$TRIVY_IMAGE" image --platform "$SCAN_PLATFORM" --severity CRITICAL --ignore-unfixed \
                            --exit-code 1 --ignorefile /trivyignore.yaml "$DOCKER_NAMESPACE/$img:$SHORT_SHA")
                        docker cp ci/trivy/.trivyignore.yaml "$cid:/trivyignore.yaml"
                        rc=0
                        docker start -a "$cid" || rc=$?
                        docker rm "$cid" > /dev/null
                        [ "$rc" -eq 0 ] || exit "$rc"
                    done
                '''
            }
        }

        // Push only after the scan passed, and only with a real DOCKER_NAMESPACE and a Jenkins
        // username/token credential with the ID 'dockerhub' (referenced by ID only). One platform:
        // push the loaded images. Several: --load cannot hold a multi-platform image, so buildx builds
        // all platforms again (the layers are cached) and pushes a manifest list straight to the registry.
        // --provenance=false keeps the manifest list to exactly the requested platforms.
        stage('Docker push') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                script {
                    if (env.DOCKER_NAMESPACE == 'change-me') {
                        echo 'DOCKER_NAMESPACE is not set, images built and scanned but not pushed.'
                    } else {
                        withCredentials([usernamePassword(credentialsId: 'dockerhub',
                                usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                            sh '''
                                echo "$DOCKERHUB_TOKEN" | docker login -u "$DOCKERHUB_USER" --password-stdin
                                case "$DOCKER_PLATFORMS" in
                                    *,*)
                                        docker buildx build --platform "$DOCKER_PLATFORMS" --provenance=false --push \
                                            -f scan-api/Dockerfile --build-arg APP_COMMIT="$SHORT_SHA" \
                                            -t "$DOCKER_NAMESPACE/scan-api:$SHORT_SHA" -t "$DOCKER_NAMESPACE/scan-api:b$BUILD_NUMBER" scan-api
                                        docker buildx build --platform "$DOCKER_PLATFORMS" --provenance=false --push \
                                            -f risk-model/Dockerfile \
                                            -t "$DOCKER_NAMESPACE/risk-model:$SHORT_SHA" -t "$DOCKER_NAMESPACE/risk-model:b$BUILD_NUMBER" risk-model
                                        ;;
                                    *)
                                        docker push "$DOCKER_NAMESPACE/scan-api:$SHORT_SHA"
                                        docker push "$DOCKER_NAMESPACE/scan-api:b$BUILD_NUMBER"
                                        docker push "$DOCKER_NAMESPACE/risk-model:$SHORT_SHA"
                                        docker push "$DOCKER_NAMESPACE/risk-model:b$BUILD_NUMBER"
                                        ;;
                                esac
                                docker logout
                            '''
                        }
                    }
                }
            }
        }
    }
}
