package com.tcs.cicd.adapters.git

import com.cloudbees.groovy.cps.NonCPS
import com.tcs.cicd.Utils

class BitbucketAdapter implements GitAdapter {
    String url
    String credentialId
    String workspace
    String apiBaseUrl

    BitbucketAdapter(Map config) {
        this.url = config.url ?: ''
        this.credentialId = config.credentialId ?: ''
        this.workspace = config.workspace ?: ''
        // Allow explicit API URL override (useful for Bitbucket Server/Data Center)
        // If not set, derive from the base URL
        this.apiBaseUrl = config.apiUrl ?: ''
    }

    @Override
    Map getCommitInfo(def script, String commitSha) {
        validateConfig(script)
        def (ws, repo) = resolveWorkspaceRepo(script)

        def response = Utils.withRetry(script) {
            script.httpRequest(
                url: "${apiUrl()}/repositories/${ws}/${repo}/commit/${commitSha}",
                authentication: credentialId,
                acceptType: 'APPLICATION_JSON',
                quiet: true,
                timeout: 30
            )
        }

        def json = script.readJSON(text: response.content)
        return [
            sha    : json.hash,
            message: json.message,
            author : json.author?.raw
        ]
    }

    @Override
    void setBuildStatus(def script, String state, String description) {
        validateConfig(script)
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

        Utils.withRetry(script) {
            script.httpRequest(
                url: "${apiUrl()}/repositories/${ws}/${repo}/commit/${commitSha}/statuses/build",
                httpMode: 'POST',
                authentication: credentialId,
                contentType: 'APPLICATION_JSON',
                requestBody: body,
                quiet: true,
                timeout: 30,
                validResponseCodes: '200:299'
            )
        }

        script.echo "Bitbucket commit status set to '${bbState}' for ${commitSha.take(7)}"
    }

    @Override
    void addPrComment(def script, String prId, String comment) {
        validateConfig(script)
        def (ws, repo) = resolveWorkspaceRepo(script)

        def bodyMap = [
            content: [raw: comment]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            Utils.withRetry(script) {
                script.httpRequest(
                    url: "${apiUrl()}/repositories/${ws}/${repo}/pullrequests/${prId}/comments",
                    httpMode: 'POST',
                    authentication: credentialId,
                    contentType: 'APPLICATION_JSON',
                    requestBody: body,
                    quiet: true,
                    timeout: 30,
                    validResponseCodes: '200:299'
                )
            }
            script.echo "Comment added to Bitbucket PR #${prId}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to add PR comment: ${e.message}"
        }
    }

    /**
     * Derive the API base URL from the configured url if apiBaseUrl is not set.
     * - Bitbucket Cloud (bitbucket.org) → https://api.bitbucket.org/2.0
     * - Bitbucket Server/Data Center → ${url}/rest/api/1.0
     */
    private String apiUrl() {
        if (apiBaseUrl) return apiBaseUrl
        if (url.contains('bitbucket.org')) {
            return 'https://api.bitbucket.org/2.0'
        }
        // Bitbucket Server / Data Center
        return "${url}/rest/api/1.0"
    }

    private void validateConfig(def script) {
        if (!url) {
            script.error "BitbucketAdapter: git.url is required in platforms config"
        }
        if (!credentialId) {
            script.error "BitbucketAdapter: git.credentialId is required in platforms config"
        }
    }

    private List resolveWorkspaceRepo(def script) {
        def remote = script.gitOps(action: 'info', field: 'remoteUrl')
        def parsed = parseRemoteUrl(remote)
        if (parsed) {
            def ws = workspace ?: parsed[0]
            return [ws, parsed[1]]
        }
        throw new RuntimeException("Cannot parse git remote: ${remote}")
    }

    @NonCPS
    private static List parseRemoteUrl(String remote) {
        def match = remote =~ /(?:.*[\/:])?([^\/]+)\/([^\/]+?)(?:\.git)?$/
        if (match.find()) {
            return [match.group(1), match.group(2)]
        }
        return null
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
