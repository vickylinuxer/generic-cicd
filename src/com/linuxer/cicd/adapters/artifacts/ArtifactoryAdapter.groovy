package com.linuxer.cicd.adapters.artifacts

class ArtifactoryAdapter implements ArtifactAdapter {
    String url
    String credentialId

    ArtifactoryAdapter(Map config) {
        this.url = config.url ?: 'https://mycompany.jfrog.io'
        this.credentialId = config.credentialId ?: 'artifactory-creds'
    }

    @Override
    void pushImage(def script, String imageName, String tag) {
        def registry = registryHost()
        def fullTag = "${registry}/${imageName}:${tag}"
        def latestTag = "${registry}/${imageName}:latest"

        script.echo "Pushing Docker image to Artifactory: ${fullTag}"

        script.withCredentials([script.usernamePassword(
            credentialsId: credentialId,
            usernameVariable: 'ARTIFACTORY_USER',
            passwordVariable: 'ARTIFACTORY_PASS'
        )]) {
            script.withEnv([
                "REGISTRY=${registry}",
                "IMAGE_NAME=${imageName}",
                "IMAGE_TAG=${tag}",
                "FULL_TAG=${fullTag}",
                "LATEST_TAG=${latestTag}"
            ]) {
                script.sh 'echo $ARTIFACTORY_PASS | docker login "$REGISTRY" -u $ARTIFACTORY_USER --password-stdin'
                script.sh 'docker tag "${IMAGE_NAME}:${IMAGE_TAG}" "$FULL_TAG"'
                script.sh 'docker tag "${IMAGE_NAME}:latest" "$LATEST_TAG"'
                script.sh 'docker push "$FULL_TAG"'
                script.sh 'docker push "$LATEST_TAG"'
                script.sh 'docker logout "$REGISTRY"'
            }
        }

        script.echo "Image pushed: ${fullTag}"
    }

    @Override
    String getImageUrl(String imageName, String tag) {
        return "${registryHost()}/${imageName}:${tag}"
    }

    @Override
    void pushRawArtifact(def script, String repoName, String remotePath, String localPath) {
        script.echo "Pushing artifact to Artifactory: ${repoName}/${remotePath}"

        script.withCredentials([script.usernamePassword(
            credentialsId: credentialId,
            usernameVariable: 'ARTIFACTORY_USER',
            passwordVariable: 'ARTIFACTORY_PASS'
        )]) {
            // Find matching files
            def files = script.findFiles(glob: localPath)

            if (!files) {
                script.echo "No files matching: ${localPath}"
                return
            }

            files.each { file ->
                script.withEnv([
                    "UPLOAD_FILE=${file.path}",
                    "FILE_NAME=${file.name}",
                    "ARTIFACTORY_URL=${url}",
                    "REPO_NAME=${repoName}",
                    "REMOTE_PATH=${remotePath}"
                ]) {
                    script.sh '''
                        curl -f -u $ARTIFACTORY_USER:$ARTIFACTORY_PASS \
                            -T "$UPLOAD_FILE" \
                            "${ARTIFACTORY_URL}/artifactory/${REPO_NAME}/${REMOTE_PATH}/${FILE_NAME}"
                    '''
                }
                script.echo "Uploaded: ${repoName}/${remotePath}/${file.name}"
            }
        }
    }

    /**
     * Extract the Docker registry host from the Artifactory URL.
     */
    private String registryHost() {
        // e.g., https://mycompany.jfrog.io → mycompany.jfrog.io
        return url.replaceAll(/^https?:\/\//, '')
    }
}
