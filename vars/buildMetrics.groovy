import groovy.json.JsonOutput

def call(Map config = [:]) {
    def metrics = [
        jobName     : env.JOB_NAME,
        buildNumber : env.BUILD_NUMBER?.toInteger(),
        result      : currentBuild.currentResult,
        duration    : currentBuild.duration,
        startTime   : currentBuild.startTimeInMillis,
        agent       : env.NODE_NAME ?: 'unknown',
        queueTime   : currentBuild.timeInMillis - currentBuild.startTimeInMillis,
        branch      : env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'N/A',
        commit      : env.GIT_COMMIT ?: 'N/A',
        prNumber    : env.CHANGE_ID ?: null,
        buildUrl    : env.BUILD_URL,
        timestamp   : System.currentTimeMillis()
    ]

    // Add project info from config if available
    def pipelineConfig = config.config
    if (pipelineConfig) {
        metrics.projectName = pipelineConfig.project?.name
        metrics.projectType = pipelineConfig.project?.type
        metrics.buildType = pipelineConfig.project?.buildType
        metrics.matrixValues = pipelineConfig._matrix
    }

    def json = JsonOutput.prettyPrint(JsonOutput.toJson(metrics))

    writeFile file: 'build-metrics.json', text: json
    archiveArtifacts artifacts: 'build-metrics.json', allowEmptyArchive: true

    echo "Build metrics collected: ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.currentResult} (${currentBuild.durationString})"
}
