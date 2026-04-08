package com.linuxer.cicd.adapters.artifacts

interface ArtifactAdapter extends Serializable {
    /**
     * Push a Docker image to the registry
     */
    void pushImage(def script, String imageName, String tag)

    /**
     * Get the full registry URL for an image
     */
    String getImageUrl(String imageName, String tag)

    /**
     * Push a raw artifact file to the repository
     */
    void pushRawArtifact(def script, String repoName, String remotePath, String localPath)
}
