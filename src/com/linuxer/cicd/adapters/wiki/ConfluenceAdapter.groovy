package com.linuxer.cicd.adapters.wiki

class ConfluenceAdapter implements WikiAdapter {
    String url
    String credentialId
    String spaceKey

    ConfluenceAdapter(Map config) {
        this.url = config.url ?: 'https://mycompany.atlassian.net/wiki'
        this.credentialId = config.credentialId ?: 'confluence-creds'
        this.spaceKey = config.spaceKey ?: 'PROJ'
    }

    @Override
    void createPage(def script, String title, String content) {
        // Confluence Cloud REST API v2
        def spaceId = resolveSpaceId(script)
        if (!spaceId) {
            script.echo "WARNING: Could not resolve Confluence space '${spaceKey}'"
            return
        }

        def bodyMap = [
            spaceId: spaceId,
            status : 'current',
            title  : title,
            body   : [
                representation: 'storage',
                value         : content
            ]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'CONFLUENCE_USER',
                passwordVariable: 'CONFLUENCE_TOKEN'
            )]) {
                script.httpRequest(
                    url: "${apiUrl()}/pages",
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
            script.echo "Page created in Confluence: ${title}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to create Confluence page '${title}': ${e.message}"
        }
    }

    @Override
    void updatePage(def script, String pageId, String title, String content) {
        // Get current version number first
        def currentVersion = getPageVersion(script, pageId)
        if (currentVersion == null) {
            script.echo "WARNING: Could not get current version for page ${pageId}"
            return
        }

        def bodyMap = [
            id     : pageId,
            status : 'current',
            title  : title,
            body   : [
                representation: 'storage',
                value         : content
            ],
            version: [
                number : currentVersion + 1,
                message: "Updated by Jenkins build #${script.env.BUILD_NUMBER}"
            ]
        ]
        def body = script.writeJSON(returnText: true, json: bodyMap)

        try {
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'CONFLUENCE_USER',
                passwordVariable: 'CONFLUENCE_TOKEN'
            )]) {
                script.httpRequest(
                    url: "${apiUrl()}/pages/${pageId}",
                    httpMode: 'PUT',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    contentType: 'APPLICATION_JSON',
                    requestBody: body,
                    quiet: true,
                    validResponseCodes: '200:299'
                )
            }
            script.echo "Page updated in Confluence: ${title}"
        } catch (Exception e) {
            script.echo "WARNING: Failed to update Confluence page '${title}': ${e.message}"
        }
    }

    /**
     * Resolve space key to space ID via Confluence Cloud v2 API.
     */
    private String resolveSpaceId(def script) {
        try {
            def response
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'CONFLUENCE_USER',
                passwordVariable: 'CONFLUENCE_TOKEN'
            )]) {
                response = script.httpRequest(
                    url: "${apiUrl()}/spaces?keys=${spaceKey}",
                    httpMode: 'GET',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    acceptType: 'APPLICATION_JSON',
                    quiet: true
                )
            }
            def json = script.readJSON(text: response.content)
            return json.results?.find { it.key == spaceKey }?.id
        } catch (Exception e) {
            script.echo "WARNING: Failed to resolve space ID: ${e.message}"
            return null
        }
    }

    /**
     * Get the current version number of a page.
     */
    private Integer getPageVersion(def script, String pageId) {
        try {
            def response
            script.withCredentials([script.usernamePassword(
                credentialsId: credentialId,
                usernameVariable: 'CONFLUENCE_USER',
                passwordVariable: 'CONFLUENCE_TOKEN'
            )]) {
                response = script.httpRequest(
                    url: "${apiUrl()}/pages/${pageId}",
                    httpMode: 'GET',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${basicAuth(script)}"]
                    ],
                    acceptType: 'APPLICATION_JSON',
                    quiet: true
                )
            }
            def json = script.readJSON(text: response.content)
            return json.version?.number as Integer
        } catch (Exception e) {
            script.echo "WARNING: Failed to get page version: ${e.message}"
            return null
        }
    }

    private String apiUrl() {
        return "${url}/api/v2"
    }

    private String basicAuth(def script) {
        return script.sh(
            script: 'echo -n "$CONFLUENCE_USER:$CONFLUENCE_TOKEN" | base64',
            returnStdout: true
        ).trim()
    }
}
