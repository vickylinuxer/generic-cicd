package com.linuxer.cicd.builders

class CustomBuilder implements Serializable {
    def script

    CustomBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def custom = config.custom ?: config.build ?: [:]
        def buildScript = custom.script ?: 'build.sh'
        def args = custom.args ?: ''
        def envVars = custom.env?.collect { k, v -> "${k}=${v}" } ?: []

        if (!script.fileExists(buildScript)) {
            script.error "Custom build script '${buildScript}' not found"
        }

        script.withEnv(envVars) {
            script.sh "chmod +x ${buildScript} && ./${buildScript} ${args}"
        }
    }
}
