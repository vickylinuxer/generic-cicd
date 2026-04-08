def call(Map params = [:]) {
    def config = params.config ?: [:]
    def platforms = params.platforms

    def adapter = platforms?.getWikiAdapter()
    if (!adapter) {
        echo "No wiki adapter configured, skipping docs publish"
        return
    }

    def projectName = config.project?.name ?: env.JOB_BASE_NAME
    def buildType = config.project?.buildType ?: 'ci'
    def docsConfig = config.publishDocs ?: params

    def title = docsConfig.title ?: "${projectName} - Build #${env.BUILD_NUMBER} (${buildType})"

    def commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    def commitSha = env.GIT_COMMIT?.take(7) ?: 'N/A'
    def branch = env.GIT_BRANCH ?: 'N/A'
    def duration = currentBuild.durationString?.replace(' and counting', '') ?: 'unknown'
    def result = currentBuild.currentResult ?: 'IN_PROGRESS'

    def content = """<h2>Build Information</h2>
<table>
<tr><td><strong>Project</strong></td><td>${projectName}</td></tr>
<tr><td><strong>Build</strong></td><td>#${env.BUILD_NUMBER}</td></tr>
<tr><td><strong>Type</strong></td><td>${buildType}</td></tr>
<tr><td><strong>Result</strong></td><td>${result}</td></tr>
<tr><td><strong>Duration</strong></td><td>${duration}</td></tr>
<tr><td><strong>Branch</strong></td><td>${branch}</td></tr>
<tr><td><strong>Commit</strong></td><td>${commitSha}</td></tr>
<tr><td><strong>Agent</strong></td><td>${env.NODE_NAME ?: 'N/A'}</td></tr>
</table>

<h3>Commit Message</h3>
<p>${commitMsg}</p>

<p><a href="${env.BUILD_URL}">View Build</a></p>"""

    // Add custom content if provided
    if (docsConfig.extraContent) {
        content += "\n\n${docsConfig.extraContent}"
    }

    def pageId = docsConfig.pageId
    if (pageId) {
        adapter.updatePage(this, pageId, title, content)
    } else {
        adapter.createPage(this, title, content)
    }
}
