package com.linuxer.cicd

class BuildOrchestrator implements Serializable {
    def script
    Map config
    Map buildTypeConfig

    BuildOrchestrator(script, Map config) {
        this.script = script
        this.config = config
        this.buildTypeConfig = config._buildTypeConfig ?: [:]
    }

    /**
     * Apply pipeline options based on config and buildType.
     * Called inside pipeline { options { } } block.
     */
    def applyOptions() {
        def timeout = config.environment?.timeout ?: 60
        script.timeout(time: timeout, unit: 'MINUTES')

        def keepBuilds = buildTypeConfig.keepBuilds
        if (keepBuilds && keepBuilds > 0) {
            script.buildDiscarder(script.logRotator(numToKeepStr: keepBuilds.toString()))
        }

        script.timestamps()
        script.ansiColor('xterm')
    }

    /**
     * Apply pipeline parameters based on buildType.
     * Called inside pipeline { parameters { } } block.
     */
    def applyParameters() {
        def params = buildTypeConfig.parameters ?: []
        params.each { param ->
            switch (param.type) {
                case 'string':
                    script.string(
                        name: param.name,
                        defaultValue: param.default ?: '',
                        description: param.description ?: ''
                    )
                    break
                case 'boolean':
                    script.booleanParam(
                        name: param.name,
                        defaultValue: param.default ?: false,
                        description: param.description ?: ''
                    )
                    break
                case 'choice':
                    script.choice(
                        name: param.name,
                        choices: param.choices ?: [],
                        description: param.description ?: ''
                    )
                    break
            }
        }
    }

    /**
     * Initialize build: set display name, trigger info, clean workspace if needed.
     */
    void initialize() {
        def buildType = config.project?.buildType ?: 'ci'
        def projectName = config.project?.name ?: script.env.JOB_BASE_NAME

        script.currentBuild.displayName = "#${script.env.BUILD_NUMBER} [${buildType}]"
        script.echo "Project: ${projectName} | Build Type: ${buildType}"
        script.echo "Agent: ${config.environment?.agent ?: 'any'}"
        script.echo "Stages: ${config.stages?.join(' → ')}"

        if (config.matrix) {
            def matrixKey = config.matrix.keySet().first()
            def matrixValues = config.matrix[matrixKey]
            script.echo "Matrix: ${matrixKey} = ${matrixValues}"
        }

        if (buildTypeConfig.cleanWorkspace) {
            script.cleanWs()
        }
    }

    /**
     * Check if notification should be sent for the given event.
     */
    boolean shouldNotify(String event) {
        def notifyOn = buildTypeConfig.notifyOn ?: ['failure']

        if (notifyOn.contains('always')) return true
        if (notifyOn.contains(event)) return true

        // 'fixed' means notify when previous build failed and this one succeeded
        if (event == 'fixed' && notifyOn.contains('fixed')) {
            def prev = script.currentBuild.previousBuild
            return prev?.result == 'FAILURE'
        }

        return false
    }

    /**
     * Check if artifacts should be published for this buildType.
     */
    boolean shouldPublishArtifacts() {
        return buildTypeConfig.publishArtifacts ?: false
    }

    /**
     * Check if build should be tagged on success.
     */
    boolean shouldTagOnSuccess() {
        return buildTypeConfig.tagOnSuccess ?: false
    }

    /**
     * Get the cron trigger expression (or null for webhook/manual).
     */
    String getCronTrigger() {
        def trigger = buildTypeConfig.trigger
        if (!trigger || trigger == 'webhook' || trigger == 'manual') {
            return null
        }
        return trigger
    }
}
