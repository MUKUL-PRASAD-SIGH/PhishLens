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

        // Skipped until scan-api/Dockerfile (M2) exists. Builds both images tagged ${SHORT_SHA} and
        // b${BUILD_NUMBER} (never :latest). The push needs a real DOCKER_NAMESPACE and a Jenkins
        // username/token credential with the ID 'dockerhub'; the credential is referenced by ID only.
        // TODO(M3): confirm the target platform (arm64 or amd64; buildx if the cluster is amd64).
        stage('Docker build & push') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                sh '''
                    docker build -f scan-api/Dockerfile --build-arg APP_COMMIT="$SHORT_SHA" \
                        -t "$DOCKER_NAMESPACE/scan-api:$SHORT_SHA" -t "$DOCKER_NAMESPACE/scan-api:b$BUILD_NUMBER" scan-api
                    docker build -f risk-model/Dockerfile \
                        -t "$DOCKER_NAMESPACE/risk-model:$SHORT_SHA" -t "$DOCKER_NAMESPACE/risk-model:b$BUILD_NUMBER" risk-model
                '''
                script {
                    if (env.DOCKER_NAMESPACE == 'change-me') {
                        echo 'DOCKER_NAMESPACE is not set, images built but not pushed.'
                    } else {
                        withCredentials([usernamePassword(credentialsId: 'dockerhub',
                                usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                            sh '''
                                echo "$DOCKERHUB_TOKEN" | docker login -u "$DOCKERHUB_USER" --password-stdin
                                docker push "$DOCKER_NAMESPACE/scan-api:$SHORT_SHA"
                                docker push "$DOCKER_NAMESPACE/scan-api:b$BUILD_NUMBER"
                                docker push "$DOCKER_NAMESPACE/risk-model:$SHORT_SHA"
                                docker push "$DOCKER_NAMESPACE/risk-model:b$BUILD_NUMBER"
                                docker logout
                            '''
                        }
                    }
                }
            }
        }

        // Skipped until scan-api/Dockerfile (M2) exists. Trivy runs as a container against the host
        // daemon (the Jenkins image has no Trivy) and fails the build on fixable CRITICAL findings.
        stage('Trivy scan') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                sh '''
                    for img in scan-api risk-model; do
                        docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache \
                            "$TRIVY_IMAGE" image --severity CRITICAL --ignore-unfixed --exit-code 1 \
                            "$DOCKER_NAMESPACE/$img:$SHORT_SHA"
                    done
                '''
            }
        }
    }
}
