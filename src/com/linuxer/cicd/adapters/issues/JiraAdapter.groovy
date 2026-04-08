package com.linuxer.cicd.adapters.issues

class JiraAdapter implements IssueAdapter {
    String url
    String credentialId
    String project

    JiraAdapter(Map config) {
        this.url = config.url ?: 'https://mycompany.atlassian.net'
        this.credentialId = config.credentialId ?: 'jira-creds'
        this.project = config.project ?: 'PROJ'
    }

    @Override
    void updateStatus(def script, String issueId, String status) {
        script.echo "Updating Jira issue ${issueId} status to ${status}"

        // Get available transitions for the issue
        def transitionId = resolveTransitionId(script, issueId, status)
        if (!transitionId) {
            script.echo "WARNING: No matching transition found for status '${status}' on ${issueId}"
            // Fall back to adding a comment
            addComment(script, issueId, "Build status: **${status}**\nJob: ${script.env.BUILD_URL}")
            return
        }

        def bodyMap = [
            transition: [id: transitionId]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'JIRA_USER',
                passwordVariable: 'JIRA_TOKEN'
            )]) {
                script.httpRequest(
                    url: "${apiUrl()}/issue/${issueId}/transitions",
                    httpMode: 'POST',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    contentType: 'APPLICATION_JSON',
                    requestBody: body,
                    quiet: true,
                    validResponseCodes: '200:299'
                )
            }
            script.echo "Jira issue ${issueId} transitioned to '${status}'"
        } catch (Exception e) {
            script.echo "WARNING: Failed to transition Jira issue ${issueId}: ${e.message}"
        }
    }

    @Override
    void addComment(def script, String issueId, String comment) {
        // Jira Cloud REST API v3 uses Atlassian Document Format (ADF)
        def bodyMap = [
            body: [
                version: 1,
                type   : 'doc',
                content: [
                    [
                        type   : 'paragraph',
                        content: [
                            [type: 'text', text: comment]
                        ]
                    ]
                ]
            ]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'JIRA_USER',
                passwordVariable: 'JIRA_TOKEN'
            )]) {
                script.httpRequest(
                    url: "${apiUrl()}/issue/${issueId}/comment",
                    httpMode: 'POST',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    contentType: 'APPLICATION_JSON',
                    requestBody: body,
                    quiet: true,
                    validResponseCodes: '200:299'
                )
            }
            script.echo "Comment added to Jira issue ${issueId}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to add comment to Jira issue ${issueId}: ${e.message}"
        }
    }

    /**
     * Resolve a Jira transition ID by matching the target status name.
     */
    private String resolveTransitionId(def script, String issueId, String targetStatus) {
        try {
            def response
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'JIRA_USER',
                passwordVariable: 'JIRA_TOKEN'
            )]) {
                response = script.httpRequest(
                    url: "${apiUrl()}/issue/${issueId}/transitions",
                    httpMode: 'GET',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    acceptType: 'APPLICATION_JSON',
                    quiet: true
                )
            }
            def json = script.readJSON(text: response.content)
            def transition = json.transitions?.find {
                it.name?.toLowerCase() == targetStatus.toLowerCase()
            }
            return transition?.id
        } catch (Exception e) {
            script.echo "WARNING: Failed to get transitions for ${issueId}: ${e.message}"
            return null
        }
    }

    private String apiUrl() {
        return "${url}/rest/api/3"
    }

    /**
     * Generate Base64-encoded basic auth from credentials.
     * Jira Cloud uses email:api-token.
     */
    private String basicAuth(def script) {
        return script.sh(
            script: 'echo -n "$JIRA_USER:$JIRA_TOKEN" | base64',
            returnStdout: true
        ).trim()
    }
}
