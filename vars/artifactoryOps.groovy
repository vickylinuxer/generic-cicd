/**
 * Extended Artifactory operations — uses JFrog Artifactory plugin (rtUpload, rtDownload, etc.).
 *
 * Actions: download, search, setProperties, promote
 *
 * Usage:
 *   artifactoryOps(action: 'download', repo: 'libs-release', path: 'com/app/1.0/app.bin', dest: 'local/')
 *   artifactoryOps(action: 'search', repo: 'libs-release', pattern: '*.bin')
 *   artifactoryOps(action: 'setProperties', repo: 'libs-release', path: 'com/app/1.0/app.bin', properties: [status: 'released'])
 *   artifactoryOps(action: 'promote', sourceRepo: 'libs-staging', targetRepo: 'libs-release', path: 'com/app/1.0/')
 */
def call(Map params = [:]) {
    def action = params.action
    if (!action) {
        error "artifactoryOps: 'action' required (download, search, setProperties, promote)"
    }

    def credentialId = params.credentialId ?: params.config?.artifacts?.credentialId ?: ''
    if (!credentialId) { error "artifactoryOps: 'credentialId' required (set artifacts.credentialId in config)" }
    def serverUrl = params.serverUrl ?: params.config?.artifacts?.url ?: env.ARTIFACTORY_URL ?: ''
    if (!serverUrl) {
        error "artifactoryOps: 'serverUrl' or ARTIFACTORY_URL env var required"
    }

    def serverId = configureServer(credentialId, serverUrl)

    switch (action) {
        case 'download':
            artDownload(params, serverId)
            break
        case 'search':
            return artSearch(params, credentialId, serverUrl)
        case 'setProperties':
            artSetProperties(params, serverId)
            break
        case 'promote':
            artPromote(params, serverId)
            break
        default:
            error "artifactoryOps: unknown action '${action}'"
    }
}

private String configureServer(String credentialId, String serverUrl) {
    def serverId = 'artifactory-server'
    rtServer(
        id: serverId,
        url: "${serverUrl}/artifactory",
        credentialsId: credentialId
    )
    return serverId
}

private void artDownload(Map params, String serverId) {
    def repo = params.repo ?: error("artifactoryOps download: 'repo' required")
    def path = params.path ?: error("artifactoryOps download: 'path' required")
    def dest = params.dest ?: '.'

    rtDownload(
        serverId: serverId,
        spec: """{
            "files": [{
                "pattern": "${repo}/${path}",
                "target": "${dest}/"
            }]
        }"""
    )
    echo "Downloaded: ${repo}/${path} → ${dest}/"
}

private String artSearch(Map params, String credentialId, String serverUrl) {
    def repo = params.repo ?: error("artifactoryOps search: 'repo' required")
    def pattern = params.pattern ?: '*'

    def response = httpRequest(
        url: "${serverUrl}/api/search/artifact?name=${pattern}&repos=${repo}",
        authentication: credentialId,
        httpMode: 'GET',
        acceptType: 'APPLICATION_JSON',
        validResponseCodes: '200',
        timeout: 60
    )

    def json = readJSON text: response.content
    def results = json.results?.collect { it.uri } ?: []
    return results.join('\n')
}

private void artSetProperties(Map params, String serverId) {
    def repo = params.repo ?: error("artifactoryOps setProperties: 'repo' required")
    def path = params.path ?: error("artifactoryOps setProperties: 'path' required")
    def properties = params.properties ?: error("artifactoryOps setProperties: 'properties' required")

    def propStr = properties.collect { k, v -> "${k}=${v}" }.join(';')

    rtSetProps(
        serverId: serverId,
        spec: """{
            "files": [{
                "pattern": "${repo}/${path}"
            }]
        }""",
        props: propStr
    )
    echo "Properties set on ${repo}/${path}: ${propStr}"
}

private void artPromote(Map params, String serverId) {
    def sourceRepo = params.sourceRepo ?: error("artifactoryOps promote: 'sourceRepo' required")
    def targetRepo = params.targetRepo ?: error("artifactoryOps promote: 'targetRepo' required")
    def path = params.path ?: error("artifactoryOps promote: 'path' required")

    rtPromote(
        serverId: serverId,
        sourceRepo: sourceRepo,
        targetRepo: targetRepo
    )
    echo "Promoted: ${sourceRepo}/${path} → ${targetRepo}/${path}"
}
