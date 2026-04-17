package com.tcs.cicd

import com.tcs.cicd.adapters.git.GitAdapter
import com.tcs.cicd.adapters.git.BitbucketAdapter
import com.tcs.cicd.adapters.artifacts.ArtifactAdapter
import com.tcs.cicd.adapters.artifacts.ArtifactoryAdapter

class PlatformFactory implements Serializable {
    def script
    Map config

    private GitAdapter _gitAdapter
    private ArtifactAdapter _artifactAdapter

    PlatformFactory(script, Map config) {
        this.script = script
        this.config = config
    }

    GitAdapter getGitAdapter() {
        if (_gitAdapter == null) {
            _gitAdapter = createGitAdapter()
        }
        return _gitAdapter
    }

    ArtifactAdapter getArtifactAdapter() {
        if (_artifactAdapter == null) {
            _artifactAdapter = createArtifactAdapter()
        }
        return _artifactAdapter
    }

    private GitAdapter createGitAdapter() {
        def gitConfig = config.git ?: [:]
        if (!gitConfig.url) {
            script.echo "git.url not configured, git adapter disabled"
            return null
        }
        def type = gitConfig.type ?: 'bitbucket'

        switch (type) {
            case 'bitbucket':
                return new BitbucketAdapter(gitConfig)
            default:
                script.echo "Unknown git adapter type: ${type}"
                return null
        }
    }

    private ArtifactAdapter createArtifactAdapter() {
        def artifactConfig = config.artifacts ?: [:]
        if (!artifactConfig.url) {
            script.echo "artifacts.url not configured, artifact adapter disabled"
            return null
        }
        def type = artifactConfig.type ?: 'artifactory'

        switch (type) {
            case 'artifactory':
                return new ArtifactoryAdapter(artifactConfig)
            default:
                script.echo "Unknown artifact adapter type: ${type}"
                return null
        }
    }
}
