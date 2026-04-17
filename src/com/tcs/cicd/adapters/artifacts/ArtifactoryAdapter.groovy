package com.tcs.cicd.adapters.artifacts

class ArtifactoryAdapter implements ArtifactAdapter {
    String url
    String credentialId

    ArtifactoryAdapter(Map config) {
        this.url = config.url ?: ''
        this.credentialId = config.credentialId ?: ''
    }

    @Override
    void pushImage(def script, String imageName, String tag) {
        validateConfig(script)
        def registry = "https://${registryHost()}"
        def fullImage = "${registryHost()}/${imageName}"

        script.echo "Pushing Docker image to Artifactory: ${fullImage}:${tag}"

        script.dockerOps(action: 'tag', image: "${imageName}:${tag}",
            targetTag: tag, registry: registry, credentialId: credentialId)

        script.dockerOps(action: 'push', image: fullImage,
            tag: tag, registry: registry, credentialId: credentialId)

        script.dockerOps(action: 'tag', image: "${imageName}:${tag}",
            targetTag: 'latest', registry: registry, credentialId: credentialId)

        script.echo "Image pushed: ${fullImage}:${tag}"
    }

    @Override
    String getImageUrl(String imageName, String tag) {
        return "${registryHost()}/${imageName}:${tag}"
    }

    @Override
    void pushRawArtifact(def script, String repoName, String remotePath, String localPath) {
        validateConfig(script)
        script.echo "Pushing artifact to Artifactory: ${repoName}/${remotePath}"

        def serverId = configureServer(script)

        script.rtUpload(
            serverId: serverId,
            spec: """{
                "files": [{
                    "pattern": "${localPath}",
                    "target": "${repoName}/${remotePath}/"
                }]
            }"""
        )

        script.echo "Uploaded: ${localPath} → ${repoName}/${remotePath}/"
    }

    /**
     * Configure Artifactory server for rtUpload/rtDownload.
     * Returns the server ID.
     */
    private String configureServer(def script) {
        def serverId = 'artifactory-server'
        script.rtServer(
            id: serverId,
            url: "${url}/artifactory",
            credentialsId: credentialId
        )
        return serverId
    }

    private String registryHost() {
        return url.replaceAll(/^https?:\/\//, '')
    }

    private void validateConfig(def script) {
        if (!url) {
            script.error "ArtifactoryAdapter: artifacts.url is required in platforms config"
        }
        if (!credentialId) {
            script.error "ArtifactoryAdapter: artifacts.credentialId is required in platforms config"
        }
    }
}
