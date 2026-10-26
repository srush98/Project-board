// =============================================================================
// Enterprise DevSecOps CI/CD Pipeline - Boardgame application
// Jira -> GitHub -> Jenkins -> Maven -> SonarQube -> Trivy -> Nexus -> Docker
//      -> Trivy image scan -> KubeAudit -> EKS -> Verification -> Email / Jira
// =============================================================================

// Sends one HTML email per build outcome, with the security reports attached.
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

    // Only constants here. Flags that change during the build (IMAGE_TAG,
    // DEPLOYED, DEPLOY_VERIFIED, APP_URL) are set with env.X in script blocks,
    // because values declared in this block cannot be overridden later.
    environment {
        IMAGE_NAME = 'srush1798/boardgame'
        NOTIFY_TO  = 'srushtijiyani2013@gmail.com'
        JIRA_SITE  = 'srushtisworkspace-40179857.atlassian.net'
    }

    stages {

        stage('Compile') {
            steps {
                sh 'mvn -B compile'
            }
        }

        stage('Test') {
            steps {
                // JaCoCo writes target/site/jacoco/jacoco.xml for SonarQube
                sh 'mvn -B test'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('sonarqube') {
                    sh '''mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                          -Dsonar.projectKey=boardgame \
                          -Dsonar.projectName=boardgame'''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                // SonarQube calls back via webhook; fail the build if the gate fails
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Trivy FS Scan') {
            steps {
                // Full report, all severities
                sh '''
                    trivy fs --scanners vuln,secret --no-progress \
                      --format table -o trivy-fs-report.txt .
                '''
                sh '''
                    trivy fs --scanners vuln,secret --no-progress \
                        --severity CRITICAL --ignore-unfixed --exit-code 1 .
                '''
            }
        }

        stage('Build') {
            steps {
                // Tests already ran in the Test stage
                sh 'mvn -B package -DskipTests'
            }
        }

        stage('Publish to Nexus') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-creds',
                                                  usernameVariable: 'NEXUS_USER',
                                                  passwordVariable: 'NEXUS_PASS')]) {
                    // Single quotes: the shell reads the secrets, Groovy never interpolates them
                    sh 'mvn -B -s ci-settings.xml deploy -DskipTests'
                }
            }
        }

        stage('Docker Build & Tag') {
            steps {
                script {
                    def sha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${sha}"   // immutable, traceable tag
                }
                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh '''
                    trivy image --no-progress --format table \
                      -o trivy-image-report.txt ${IMAGE_NAME}:${IMAGE_TAG}

                    # Hard gate on OS packages; Java library CVEs are covered by KAN-2
                    trivy image --no-progress \
                      --severity CRITICAL --ignore-unfixed --exit-code 1 \
                      ${IMAGE_NAME}:${IMAGE_TAG}
                '''
            }
        }

        stage('Push to Docker Hub') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                                  usernameVariable: 'DH_USER',
                                                  passwordVariable: 'DH_TOKEN')]) {
                    sh '''
                        echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
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
                // Audit exactly what will be deployed (rendered with the real image tag)
                sh '''
                    mkdir -p rendered
                    sed "s|IMAGE_PLACEHOLDER|${IMAGE_NAME}:${IMAGE_TAG}|" k8s/deployment.yaml > rendered/deployment.yaml
                    cp k8s/service.yaml rendered/service.yaml

                    set +e
                    kubeaudit all -k kubeaudit-config.yaml -f rendered/deployment.yaml > kubeaudit-manifest-report.txt 2>&1
                    rc=$?
                    set -e
                    cat kubeaudit-manifest-report.txt
                    exit $rc
                '''
            }
        }

        stage('Deploy to EKS') {
            steps {
                sh '''
                    aws eks update-kubeconfig --name devsecops-eks --region us-east-1
                    kubectl apply -f rendered/
                '''
                // From here on, a failure must trigger a rollback
                script { env.DEPLOYED = 'true' }
                sh '''
                    kubectl annotate deployment/boardgame -n boardgame --overwrite \
                      kubernetes.io/change-cause="Jenkins build ${BUILD_NUMBER}, image ${IMAGE_TAG}"
                '''
            }
        }

        stage('Verify Deployment') {
            steps {
                sh '''
                    kubectl rollout status deployment/boardgame -n boardgame --timeout=300s
                    kubectl get pods,svc -n boardgame -o wide

                    echo "Waiting for load balancer hostname..."
                    LB=""
                    for i in $(seq 1 30); do
                        LB=$(kubectl get svc boardgame -n boardgame \
                              -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
                        [ -n "$LB" ] && break
                        sleep 10
                    done
                    [ -n "$LB" ] || { echo "No load balancer hostname"; exit 1; }
                    echo "App URL: http://$LB"

                    for i in $(seq 1 30); do
                        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://$LB/" || true)
                        echo "Smoke test attempt $i: HTTP $code"
                        [ "$code" = "200" ] && exit 0
                        sleep 10
                    done
                    echo "Smoke test failed"
                    exit 1
                '''
                script {
                    env.APP_URL = 'http://' + sh(
                        script: "kubectl get svc boardgame -n boardgame -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'",
                        returnStdout: true).trim()
                    env.DEPLOY_VERIFIED = 'true'   // only reached if rollout + smoke test passed
                }
            }
            post {
                always {
                    // Deployment state comes from verification, not from the overall
                    // build result (which is UNSTABLE because of KAN-2)
                    jiraSendDeploymentInfo site: "${env.JIRA_SITE}",
                                           environmentId: 'eks-boardgame-prod',
                                           environmentName: 'production',
                                           environmentType: 'production',
                                           state: (env.DEPLOY_VERIFIED == 'true' ? 'successful' : 'failed')
                }
            }
        }

        stage('KubeAudit (live)') {
            steps {
                // Report only: the gate already ran on the manifests before deploying
                sh '''
                    kubeaudit all -n boardgame -k kubeaudit-config.yaml > kubeaudit-live-report.txt 2>&1 || true
                    cat kubeaudit-live-report.txt
                '''
            }
        }
    }

    post {
        always {
            jiraSendBuildInfo site: "${env.JIRA_SITE}"
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
            archiveArtifacts artifacts: 'trivy-*.txt, kubeaudit-*.txt, rendered/*.yaml',
                             allowEmptyArchive: true
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
