def call(Map params = [:]) {
    def config = params.config ?: [:]
    def platforms = params.platforms
    def status = params.status ?: currentBuild.currentResult

    def adapter = platforms?.getGitAdapter()
    if (!adapter) {
        echo "No git adapter configured, skipping build status notification"
        return
    }

    def state = status == 'SUCCESS' ? 'success' :
                status == 'FAILURE' ? 'failure' : 'pending'

    def description = "${env.JOB_NAME} #${env.BUILD_NUMBER} - ${status}"

    try {
        adapter.setBuildStatus(this, state, description)
    } catch (Exception e) {
        echo "WARNING: Failed to set build status: ${e.message}"
    }

    // Add PR comment if this is a PR build
    if (env.CHANGE_ID) {
        def duration = currentBuild.durationString?.replace(' and counting', '') ?: 'unknown'
        def comment = "**Build ${status}** - #${env.BUILD_NUMBER}\n" +
                      "Duration: ${duration}\n" +
                      "[View Build](${env.BUILD_URL})"
        try {
            adapter.addPrComment(this, env.CHANGE_ID, comment)
        } catch (Exception e) {
            echo "WARNING: Failed to add PR comment: ${e.message}"
        }
    }
}
