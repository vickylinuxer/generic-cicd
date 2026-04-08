package com.linuxer.cicd

import com.linuxer.cicd.builders.BuilderFactory

class StageRunner implements Serializable {
    static final Map BUILT_IN = [
        'checkout'      : 'gitCheckout',
        'build'         : null,             // delegates to BuilderFactory
        'staticAnalysis': 'staticAnalysis',
        'unitTest'      : 'unitTest',
        'test'          : 'unitTest',
        'publish'       : 'publishArtifact',
        'publishImage'  : 'publishImage',
        'updateIssue'   : 'updateIssue',
        'publishDocs'   : 'publishDocs',
        'notify'        : 'notifyBuild',
        'deploy'        : 'customBuild'
    ]

    def script
    Map config
    PlatformFactory platforms

    StageRunner(script, Map config, PlatformFactory platforms) {
        this.script = script
        this.config = config
        this.platforms = platforms
    }

    /**
     * Resolve and run a named stage.
     * Order: built-in → convention script → error
     */
    void run(String stageName) {
        script.stage(stageName) {
            if (BUILT_IN.containsKey(stageName)) {
                runBuiltIn(stageName)
            } else if (script.fileExists("stages/${stageName}.sh")) {
                runConvention(stageName)
            } else {
                script.error "Unknown stage '${stageName}' and no stages/${stageName}.sh found"
            }
        }
    }

    /**
     * Run a built-in stage by delegating to the corresponding vars/ step.
     */
    private void runBuiltIn(String stageName) {
        def varName = BUILT_IN[stageName]

        if (stageName == 'build') {
            // Delegate to BuilderFactory based on project.type
            def builder = BuilderFactory.create(script, config)
            builder.build(config)
            return
        }

        def stageConfig = config[stageName] ?: [:]
        def stepConfig = [
            config   : config,
            platforms: platforms,
        ] + stageConfig

        // Call the vars/ global function by name
        script."${varName}"(stepConfig)
    }

    /**
     * Run a convention-based stage from stages/{name}.sh.
     * Stage config is injected as environment variables.
     */
    void runConvention(String stageName) {
        def stageConfig = config[stageName] ?: [:]
        def envVars = stageConfig.env?.collect { k, v -> "${k}=${v}" } ?: []
        def args = stageConfig.args ?: ""

        script.withEnv(envVars) {
            script.sh "chmod +x stages/${stageName}.sh && ./stages/${stageName}.sh ${args}"
        }
    }

    /**
     * Run post-build actions (always, success, failure).
     */
    void post(String condition) {
        switch (condition) {
            case 'always':
                script.buildMetrics(config: config)
                break
            case 'success':
                def orchestrator = new BuildOrchestrator(script, config)
                if (orchestrator.shouldNotify('success') || orchestrator.shouldNotify('fixed')) {
                    script.notifyBuild(config: config, platforms: platforms, status: 'SUCCESS')
                }
                if (orchestrator.shouldTagOnSuccess()) {
                    tagBuild()
                }
                break
            case 'failure':
                def orchestrator = new BuildOrchestrator(script, config)
                if (orchestrator.shouldNotify('failure')) {
                    script.notifyBuild(config: config, platforms: platforms, status: 'FAILURE')
                    script.notifyEmail(status: 'FAILURE')
                }
                break
        }
    }

    private void tagBuild() {
        def version = script.params.VERSION ?: script.env.BUILD_NUMBER
        def tag = "v${version}"
        try {
            script.sh "git tag -a ${tag} -m 'Release ${tag} (build #${script.env.BUILD_NUMBER})'"
            script.sh "git push origin ${tag}"
            script.echo "Tagged build as ${tag}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to tag build: ${e.message}"
        }
    }
}
