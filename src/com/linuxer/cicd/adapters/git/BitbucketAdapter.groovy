package com.linuxer.cicd.adapters.git

class BitbucketAdapter implements GitAdapter {
    String url
    String credentialId
    String workspace

    BitbucketAdapter(Map config) {
        this.url = config.url ?: 'https://bitbucket.org'
        this.credentialId = config.credentialId ?: 'bitbucket-creds'
        this.workspace = config.workspace ?: ''
    }

    @Override
    Map getCommitInfo(def script, String commitSha) {
        def (ws, repo) = resolveWorkspaceRepo(script)

        def response = script.httpRequest(
            url: "${apiUrl()}/repositories/${ws}/${repo}/commit/${commitSha}",
            authentication: credentialId,
            acceptType: 'APPLICATION_JSON',
            quiet: true
        )

        def json = script.readJSON(text: response.content)
        return [
            sha    : json.hash,
            message: json.message,
            author : json.author?.raw
        ]
    }

    @Override
    void setBuildStatus(def script, String state, String description) {
        def (ws, repo) = resolveWorkspaceRepo(script)
        def commitSha = script.env.GIT_COMMIT

        def bbState = mapState(state)
        def bodyMap = [
            state      : bbState,
            key        : "jenkins-${script.env.JOB_NAME}",
            name       : "Jenkins Build #${script.env.BUILD_NUMBER}",
            url        : script.env.BUILD_URL,
            description: description
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        script.httpRequest(
            url: "${apiUrl()}/repositories/${ws}/${repo}/commit/${commitSha}/statuses/build",
            httpMode: 'POST',
            authentication: credentialId,
            contentType: 'APPLICATION_JSON',
            requestBody: body,
            quiet: true,
            validResponseCodes: '200:299'
        )

        script.echo "Bitbucket commit status set to '${bbState}' for ${commitSha.take(7)}"
    }

    @Override
    void addPrComment(def script, String prId, String comment) {
        def (ws, repo) = resolveWorkspaceRepo(script)

        def bodyMap = [
            content: [raw: comment]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            script.httpRequest(
                url: "${apiUrl()}/repositories/${ws}/${repo}/pullrequests/${prId}/comments",
                httpMode: 'POST',
                authentication: credentialId,
                contentType: 'APPLICATION_JSON',
                requestBody: body,
                quiet: true,
                validResponseCodes: '200:299'
            )
            script.echo "Comment added to Bitbucket PR #${prId}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to add PR comment: ${e.message}"
        }
    }

    private String apiUrl() {
        // Bitbucket Cloud API 2.0
        return 'https://api.bitbucket.org/2.0'
    }

    /**
     * Resolve workspace and repo slug from git remote or config.
     */
    private List resolveWorkspaceRepo(def script) {
        def remote = script.sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def match = remote =~ /(?:.*[\/:])?([^\/]+)\/([^\/]+?)(?:\.git)?$/
        if (match.find()) {
            def ws = workspace ?: match.group(1)
            def repo = match.group(2)
            return [ws, repo]
        }
        throw new RuntimeException("Cannot parse git remote: ${remote}")
    }

    private static String mapState(String state) {
        switch (state.toLowerCase()) {
            case 'success': return 'SUCCESSFUL'
            case 'failure': return 'FAILED'
            case 'error':   return 'FAILED'
            case 'pending': return 'INPROGRESS'
            default:        return 'INPROGRESS'
        }
    }
}
