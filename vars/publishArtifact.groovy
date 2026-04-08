def call(Map params = [:]) {
    def config = params.config ?: [:]
    def platforms = params.platforms
    def publishConfig = config.publish ?: params

    def adapter = platforms?.getArtifactAdapter()
    if (!adapter) {
        echo "No artifact adapter configured, archiving locally only"
        archiveLocal(publishConfig)
        return
    }

    def artifacts = publishConfig.artifacts ?: []
    def buildType = config.project?.buildType ?: 'ci'
    def projectName = config.project?.name ?: env.JOB_BASE_NAME

    // Determine remote path based on naming pattern
    def namingPattern = config.artifacts?.namingPattern ?: '${PROJECT}/${BUILD_TYPE}/${BUILD_NUMBER}'
    def releasePattern = config.artifacts?.releasePattern ?: '${PROJECT}/releases/${VERSION}'

    def remotePath
    if (buildType == 'release' && params.VERSION) {
        remotePath = releasePattern
            .replace('${PROJECT}', projectName)
            .replace('${VERSION}', params.VERSION ?: env.BUILD_NUMBER)
    } else {
        remotePath = namingPattern
            .replace('${PROJECT}', projectName)
            .replace('${BUILD_TYPE}', buildType)
            .replace('${BUILD_NUMBER}', env.BUILD_NUMBER)
    }

    // Inject matrix value into path if present
    def matrixValue = config._matrix?.values()?.first()
    if (matrixValue) {
        remotePath = "${remotePath}/${matrixValue}"
    }

    if (artifacts) {
        // Publish each artifact entry
        artifacts.each { artifact ->
            def pattern = artifact.pattern ?: artifact
            def repo = artifact.repo ?: 'generic-local'
            echo "Publishing: ${pattern} → ${repo}/${remotePath}"
            adapter.pushRawArtifact(this, repo, remotePath, pattern)
        }
    } else {
        echo "No artifact patterns defined in publish config"
    }

    // Also archive locally in Jenkins
    archiveLocal(publishConfig)
}

private void archiveLocal(Map publishConfig) {
    def artifacts = publishConfig.artifacts ?: []
    artifacts.each { artifact ->
        def pattern = artifact instanceof Map ? artifact.pattern : artifact
        if (pattern) {
            try {
                archiveArtifacts artifacts: pattern, allowEmptyArchive: true
            } catch (Exception e) {
                echo "Local archive: ${e.message}"
            }
        }
    }
}
