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

        // Placeholder, not implemented yet. Skipped until scan-api/Dockerfile (M2) exists.
        // TODO(M3): confirm registry + platform. Needs the Docker Hub namespace (SPEC 3.4 says
        // phishlens/, which isn't ours) and whether the cluster is arm64 or amd64 (buildx if amd64).
        // Plan: build phishlens/scan-api and phishlens/risk-model tagged ${SHORT_SHA} and
        // b${BUILD_NUMBER} (never :latest), --build-arg APP_COMMIT=${SHORT_SHA}, push with a
        // Jenkins credential referenced by ID only.
        stage('Docker build & push') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                echo 'Docker build & push: placeholder, waiting on registry namespace and target platform (TODO(M3)).'
            }
        }

        // Placeholder, not implemented yet. Skipped until scan-api/Dockerfile (M2) exists.
        // TODO(M3): confirm registry + platform; the scan runs on the images built above.
        // Plan: trivy image --severity CRITICAL --ignore-unfixed --exit-code 1 on both images.
        stage('Trivy scan') {
            when {
                expression { fileExists('scan-api/Dockerfile') }
            }
            steps {
                echo 'Trivy scan: placeholder, runs once the Docker stage builds real images (TODO(M3)).'
            }
        }
    }
}
