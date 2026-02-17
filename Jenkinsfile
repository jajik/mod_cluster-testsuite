pipeline {
    agent any

    parameters {
        choice(name: 'WILDFLY_VERSION', choices: ['31.0.1.Final', '30.0.1.Final', '29.0.1.Final'],
               description: 'WildFly version to test (used for pre-built images)')
        string(name: 'WILDFLY_ZIP_PATH', defaultValue: '',
               description: 'Path to WildFly/EAP ZIP distribution (optional, will build from ZIP if provided)')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
        timeout(time: 2, unit: 'HOURS')
    }

    tools {
        maven 'Maven 3.9'
        jdk 'JDK 11'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean compile -DskipTests'
            }
        }

        stage('Test Matrix') {
            matrix {
                axes {
                    axis {
                        name 'BALANCER_TYPE'
                        values 'undertow', 'httpd'
                    }
                }

                stages {
                    stage('Run Tests') {
                        steps {
                            script {
                                def balancerType = env.BALANCER_TYPE
                                echo "Running tests with balancer: ${balancerType}"

                                def zipPathParam = params.WILDFLY_ZIP_PATH ? "-Dwildfly.zip.path=${params.WILDFLY_ZIP_PATH}" : ""

                                sh """
                                    mvn test -P${balancerType} \\
                                        -Dbalancer.type=${balancerType} \\
                                        -Dwildfly.version=${params.WILDFLY_VERSION} \\
                                        ${zipPathParam} \\
                                        -Dtestcontainers.reuse.enable=false
                                """
                            }
                        }

                        post {
                            always {
                                junit testResults: '**/target/surefire-reports/*.xml',
                                      allowEmptyResults: true

                                // Archive test results per balancer type
                                archiveArtifacts artifacts: '**/target/surefire-reports/*.xml',
                                                allowEmptyArchive: true,
                                                fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            // Cleanup containers
            sh 'docker container prune -f || true'
            sh 'docker network prune -f || true'
        }

        success {
            echo 'All tests passed!'
        }

        failure {
            echo 'Tests failed!'
            // Send notifications here
        }

        unstable {
            echo 'Tests are unstable!'
        }
    }
}
