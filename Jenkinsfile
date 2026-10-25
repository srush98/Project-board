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
        
                    trivy fs --scanners vuln,secret --no-progress \
                      --severity CRITICAL --ignore-unfixed --exit-code 1 .
                '''
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
    }
}
