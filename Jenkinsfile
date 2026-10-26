def notifyBuild(String status, String color) {
    emailext(
        to: "${env.NOTIFY_TO}",
        subject: "[${status}] ${env.JOB_NAME} #${env.BUILD_NUMBER}",
        mimeType: 'text/html',
        attachLog: status == 'FAILURE',
        compressLog: true,
        attachmentsPattern: 'trivy-fs-report.txt,trivy-image-report.txt,kubeaudit-*.txt',
        body: """
            <h2 style="color:${color}">Pipeline ${status}</h2>
            <table cellpadding="4">
              <tr><td><b>Job</b></td><td>${env.JOB_NAME} #${env.BUILD_NUMBER}</td></tr>
              <tr><td><b>Image</b></td><td>${env.IMAGE_NAME}:${env.IMAGE_TAG ?: 'n/a'}</td></tr>
              <tr><td><b>App URL</b></td><td>${env.APP_URL ?: 'not deployed'}</td></tr>
              <tr><td><b>Build</b></td><td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td></tr>
              <tr><td><b>Reports</b></td><td><a href="${env.BUILD_URL}artifact/">Build artifacts</a> (scan reports attached)</td></tr>
            </table>
        """
    )
}

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        IMAGE_NAME = 'srush1798/boardgame'
        NOTIFY_TO  = 'srushtijiyani2013@gmail.com'
        DEPLOYED   = 'false'
        JIRA_SITE = 'srushtisworkspace-40179857.atlassian.net'
    }

    stages {
        stage('Compile') {
            steps {
                sh 'mvn -B compile'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B test'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('sonarqube') {
                    sh '''
                        mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                            -Dsonar.projectKey=boardgame \
                            -Dsonar.projectName=boardgame
                    '''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Trivy FS Scan') {
            steps {
                sh '''
                    trivy fs --scanners vuln,secret --no-progress \
                        --format table -o trivy-fs-report.txt .
                '''

                // KAN-2: Temporary risk acceptance for Spring Boot 2.5.6 CVEs.
                // Review by <date+30d>.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh '''
                        trivy fs --scanners vuln,secret --no-progress \
                            --severity CRITICAL --ignore-unfixed \
                            --exit-code 1 .
                    '''
                }
            }

            post {
                always {
                    archiveArtifacts(
                        artifacts: 'trivy-fs-report.txt',
                        allowEmptyArchive: true
                    )
                }
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B package -DskipTests'
            }
        }

        stage('Publish to Nexus') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'nexus-creds',
                        usernameVariable: 'NEXUS_USER',
                        passwordVariable: 'NEXUS_PASS'
                    )
                ]) {
                    sh 'mvn -B -s ci-settings.xml deploy -DskipTests'
                }
            }
        }

        stage('Docker Build & Tag') {
            steps {
                script {
                    def sha = sh(
                        script: 'git rev-parse --short=7 HEAD',
                        returnStdout: true
                    ).trim()

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${sha}"
                }

                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh '''
                    trivy image --no-progress --format table \
                        -o trivy-image-report.txt ${IMAGE_NAME}:${IMAGE_TAG}

                    trivy image --no-progress --pkg-types os \
                        --severity CRITICAL --ignore-unfixed \
                        --exit-code 1 ${IMAGE_NAME}:${IMAGE_TAG}
                '''
            }

            post {
                always {
                    archiveArtifacts(
                        artifacts: 'trivy-image-report.txt',
                        allowEmptyArchive: true
                    )
                }
            }
        }

        stage('Push to Docker Hub') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'dockerhub-creds',
                        usernameVariable: 'DH_USER',
                        passwordVariable: 'DH_TOKEN'
                    )
                ]) {
                    sh '''
                        echo "$DH_TOKEN" | docker login \
                            -u "$DH_USER" --password-stdin

                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    '''
                }
            }

            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        stage('KubeAudit (manifests)') {
            steps {
                sh '''
                    mkdir -p rendered

                    sed "s|IMAGE_PLACEHOLDER|${IMAGE_NAME}:${IMAGE_TAG}|" \
                        k8s/deployment.yaml > rendered/deployment.yaml

                    cp k8s/service.yaml rendered/service.yaml

                    set +e
                    kubeaudit all -k kubeaudit-config.yaml \
                        -f rendered/deployment.yaml \
                        > kubeaudit-manifest-report.txt 2>&1
                    rc=$?
                    set -e

                    cat kubeaudit-manifest-report.txt
                    exit $rc
                '''
            }

            post {
                always {
                    archiveArtifacts(
                        artifacts: 'kubeaudit-manifest-report.txt, rendered/*.yaml',
                        allowEmptyArchive: true
                    )
                }
            }
        }

        stage('Deploy to EKS') {
            steps {
                sh '''
                    aws eks update-kubeconfig \
                        --name devsecops-eks \
                        --region us-east-1

                    kubectl apply -f rendered/

                    kubectl annotate deployment/boardgame \
                        -n boardgame \
                        --overwrite \
                        kubernetes.io/change-cause="Jenkins build ${BUILD_NUMBER}, image ${IMAGE_TAG}"
                '''
                script { env.DEPLOYED = 'true' }
            }
        }

        stage('Verify Deployment') {
            steps {
                sh '''
                    kubectl rollout status deployment/boardgame \
                        -n boardgame --timeout=300s

                    kubectl get pods,svc -n boardgame -o wide

                    echo "Waiting for load balancer hostname..."

                    LB=""

                    for i in $(seq 1 30); do
                        LB=$(kubectl get svc boardgame -n boardgame \
                            -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

                        [ -n "$LB" ] && break
                        sleep 10
                    done

                    [ -n "$LB" ] || {
                        echo "No load balancer hostname"
                        exit 1
                    }

                    echo "App URL: http://$LB"

                    for i in $(seq 1 30); do
                        code=$(curl -s -o /dev/null -w "%{http_code}" \
                            --max-time 5 "http://$LB/" || true)

                        echo "Smoke test attempt $i: HTTP $code"

                        [ "$code" = "200" ] && exit 0
                        sleep 10
                    done

                    echo "Smoke test failed"
                    exit 1
                '''
            }
            post {
                always {
                    jiraSendDeploymentInfo site: "${env.JIRA_SITE}",
                                           environmentId: 'eks-boardgame-prod',
                                           environmentName: 'production',
                                           environmentType: 'production'
                }
            }
        }

        stage('KubeAudit (live)') {
            steps {
                sh '''
                    kubeaudit all -n boardgame \
                        -k kubeaudit-config.yaml \
                        > kubeaudit-live-report.txt 2>&1 || true

                    cat kubeaudit-live-report.txt
                '''
                        script {
                            env.APP_URL = 'http://' + sh(
                                script: "kubectl get svc boardgame -n boardgame -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'",
                                returnStdout: true).trim()
                        }
            }

           post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'trivy-*.txt, kubeaudit-*.txt, rendered/*.yaml',
                                     allowEmptyArchive: true
                    jiraSendBuildInfo site: "${env.JIRA_SITE}"
                }
                success {
                    notifyBuild('SUCCESS', 'green')
                }
                unstable {
                    notifyBuild('UNSTABLE', 'orange')
                }
                failure {
                    script {
                        if (env.DEPLOYED == 'true') {
                            echo 'Deployment failed after apply: rolling back to previous version'
                            sh 'kubectl rollout undo deployment/boardgame -n boardgame || true'
                        }
                    }
                    notifyBuild('FAILURE', 'red')
                }
                cleanup {
                    sh '''
                        if [ -n "$IMAGE_TAG" ]; then docker rmi "$IMAGE_NAME:$IMAGE_TAG" || true; fi
                        docker image prune -f || true
                    '''
                    cleanWs()
                }
            }
        }
    }
}
