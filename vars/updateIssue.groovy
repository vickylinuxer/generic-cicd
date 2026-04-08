def call(Map params = [:]) {
    def config = params.config ?: [:]
    def platforms = params.platforms

    def adapter = platforms?.getIssueAdapter()
    if (!adapter) {
        echo "No issue adapter configured, skipping issue update"
        return
    }

    // Extract issue ID from commit message (pattern: PROJ-123)
    def commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    def issuePattern = config.updateIssue?.pattern ?: '[A-Z]+-\\d+'
    def matcher = commitMsg =~ /${issuePattern}/

    if (!matcher.find()) {
        echo "No issue ID found in commit message, skipping"
        return
    }

    def issueId = matcher.group(0)
    echo "Found issue: ${issueId}"

    // Build comment
    def status = currentBuild.currentResult ?: 'IN_PROGRESS'
    def comment = """Build ${status}
Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Branch: ${env.GIT_BRANCH ?: 'N/A'}
Commit: ${env.GIT_COMMIT?.take(7) ?: 'N/A'}
URL: ${env.BUILD_URL}"""

    adapter.addComment(this, issueId, comment)

    // Transition issue if configured
    def transitionMap = config.updateIssue?.transitions ?: [:]
    def targetStatus = transitionMap[status.toLowerCase()]
    if (targetStatus) {
        adapter.updateStatus(this, issueId, targetStatus)
    }
}
