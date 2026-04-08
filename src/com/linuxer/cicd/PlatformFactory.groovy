package com.linuxer.cicd

import com.linuxer.cicd.adapters.git.GitAdapter
import com.linuxer.cicd.adapters.git.BitbucketAdapter
import com.linuxer.cicd.adapters.artifacts.ArtifactAdapter
import com.linuxer.cicd.adapters.artifacts.ArtifactoryAdapter
import com.linuxer.cicd.adapters.issues.IssueAdapter
import com.linuxer.cicd.adapters.issues.JiraAdapter
import com.linuxer.cicd.adapters.wiki.WikiAdapter
import com.linuxer.cicd.adapters.wiki.ConfluenceAdapter

class PlatformFactory implements Serializable {
    def script
    Map config

    private GitAdapter _gitAdapter
    private ArtifactAdapter _artifactAdapter
    private IssueAdapter _issueAdapter
    private WikiAdapter _wikiAdapter

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

    IssueAdapter getIssueAdapter() {
        if (_issueAdapter == null) {
            _issueAdapter = createIssueAdapter()
        }
        return _issueAdapter
    }

    WikiAdapter getWikiAdapter() {
        if (_wikiAdapter == null) {
            _wikiAdapter = createWikiAdapter()
        }
        return _wikiAdapter
    }

    private GitAdapter createGitAdapter() {
        def gitConfig = config.git ?: [:]
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
        def type = artifactConfig.type ?: 'artifactory'

        switch (type) {
            case 'artifactory':
                return new ArtifactoryAdapter(artifactConfig)
            default:
                script.echo "Unknown artifact adapter type: ${type}"
                return null
        }
    }

    private IssueAdapter createIssueAdapter() {
        def issueConfig = config.issues
        if (!issueConfig) return null
        def type = issueConfig.type ?: 'jira'

        switch (type) {
            case 'jira':
                return new JiraAdapter(issueConfig)
            default:
                script.echo "Unknown issue adapter type: ${type}"
                return null
        }
    }

    private WikiAdapter createWikiAdapter() {
        def wikiConfig = config.wiki
        if (!wikiConfig) return null
        def type = wikiConfig.type ?: 'confluence'

        switch (type) {
            case 'confluence':
                return new ConfluenceAdapter(wikiConfig)
            default:
                script.echo "Unknown wiki adapter type: ${type}"
                return null
        }
    }
}
