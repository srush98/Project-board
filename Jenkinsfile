pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }
    
    environment {
       IMAGE_NAME = 'srush1798/boardgame'
   }

    stages {
        stage('Compile') {
            steps { sh 'mvn -B compile' }
        }
        stage('Test') {
            steps { sh 'mvn -B test' }
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
                // KAN-2: temporary risk acceptance (Spring Boot 2.5.6 CVEs). Review by <date+30d>.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh '''
                        trivy fs --scanners vuln,secret --no-progress \
                          --severity CRITICAL --ignore-unfixed --exit-code 1 .
                    '''
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'trivy-fs-report.txt', allowEmptyArchive: true
                }
            }
        }
        
        stage('Build') {
            steps { sh 'mvn -B package -DskipTests' }
        }
        
        stage('Publish to Nexus') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-creds',
                                                  usernameVariable: 'NEXUS_USER',
                                                  passwordVariable: 'NEXUS_PASS')]) {
                    sh 'mvn -B -s ci-settings.xml deploy -DskipTests'
                }
            }
        }

        stage('Docker Build & Tag') {
           steps {
               script {
                   def sha = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
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
                     --severity CRITICAL --ignore-unfixed --exit-code 1 \
                     ${IMAGE_NAME}:${IMAGE_TAG}
               '''
           }
           post {
               always {
                   archiveArtifacts artifacts: 'trivy-image-report.txt', allowEmptyArchive: true
               }
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
               always { sh 'docker logout || true' }
           }
       }    
    }
}
