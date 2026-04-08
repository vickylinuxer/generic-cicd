import com.linuxer.cicd.ConfigLoader
import com.linuxer.cicd.BuildOrchestrator
import com.linuxer.cicd.PlatformFactory
import com.linuxer.cicd.StageRunner
import com.linuxer.cicd.MatrixRunner

def call(Map overrides = [:]) {
    def config = new ConfigLoader(this).load(overrides)
    def orchestrator = new BuildOrchestrator(this, config)
    def platforms = new PlatformFactory(this, config)
    def stageRunner = new StageRunner(this, config, platforms)

    def agentLabel = config.environment?.agent ?: 'any'
    def dockerImage = config.environment?.docker?.image ?: ''

    pipeline {
        agent {
            if (dockerImage) {
                docker {
                    image dockerImage
                    args config.environment?.docker?.args ?: ''
                    label agentLabel != 'any' ? agentLabel : ''
                }
            } else if (agentLabel == 'any') {
                any
            } else {
                label agentLabel
            }
        }

        options {
            script { orchestrator.applyOptions() }
        }

        parameters {
            script { orchestrator.applyParameters() }
        }

        triggers {
            script {
                def cron = orchestrator.getCronTrigger()
                if (cron) {
                    cron(cron)
                }
            }
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        orchestrator.initialize()
                    }
                }
            }

            stage('Pipeline') {
                steps {
                    script {
                        if (config.matrix) {
                            new MatrixRunner(this, config, stageRunner).run()
                        } else {
                            config.stages.each { stageName ->
                                stageRunner.run(stageName)
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script { stageRunner.post('always') }
            }
            success {
                script { stageRunner.post('success') }
            }
            failure {
                script { stageRunner.post('failure') }
            }
        }
    }
}
