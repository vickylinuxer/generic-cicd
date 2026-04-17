/**
 * Source code management operations — replaces fetchSources.
 *
 * Dispatches by type: git, repo
 *
 * Usage:
 *   scmOps(config: [checkout: [manifest: [
 *     [type: 'git', name: 'app', url: 'https://...', branch: 'main', dir: 'app'],
 *     [type: 'repo', name: 'aosp', url: 'https://...', branch: 'main', dir: 'aosp', jobs: 4]
 *   ]]])
 */
def call(Map params = [:]) {
    def config = params.config ?: [:]
    def defaultGitCredId = config.credentials?.git ?: config.git?.credentialId ?: ''
    def manifest = config.checkout?.manifest ?: []

    if (!manifest) {
        echo "No checkout.manifest defined, skipping scmOps"
        return
    }

    for (entry in manifest) {
        def type = entry.type ?: 'git'
        switch (type) {
            case 'git':
                fetchGit(entry, defaultGitCredId)
                break
            case 'repo':
                fetchRepo(entry, defaultGitCredId)
                break
            default:
                error "scmOps: unknown source type '${type}'"
        }
    }
}

/**
 * Git fetch — delegates to gitOps clone.
 */
private void fetchGit(Map entry, String defaultGitCredId = '') {
    if (!entry.url) {
        error "scmOps: manifest entry '${entry.name}': url is required for type 'git'"
    }
    if (!entry.dir) {
        error "scmOps: manifest entry '${entry.name}': dir is required for type 'git'"
    }

    echo "Fetching git source: ${entry.name} (${entry.url} @ ${entry.branch ?: 'main'}) -> ${entry.dir}"

    def cloneParams = [action: 'clone', url: entry.url, branch: entry.branch ?: 'main',
                        dir: entry.dir, credentialId: entry.credentialId ?: defaultGitCredId, clean: true]
    if (entry.reference) {
        cloneParams.reference = entry.reference
    }
    gitOps(cloneParams)
}

/**
 * Repo sync via Jenkins RepoSCM plugin.
 * Uses checkout([$class: 'RepoScm', ...]) instead of shell repo command.
 */
private void fetchRepo(Map entry, String defaultGitCredId = '') {
    def url = entry.url
    def branch = entry.branch ?: 'main'
    def targetDir = entry.dir ?: ''
    def jobs = entry.jobs ?: 4
    def reference = entry.reference ?: ''
    def manifestFile = entry.file ?: 'default.xml'
    def clean = entry.clean ?: false
    def depth = entry.depth ?: 0
    def credentialId = entry.credentialId ?: defaultGitCredId

    if (!url) {
        error "scmOps: manifest entry '${entry.name}': url is required for type 'repo'"
    }

    echo "Syncing repo: ${entry.name} (${url} @ ${branch}) -> ${targetDir ?: 'workspace'}"

    def repoParams = [
        $class: 'RepoScm',
        manifestRepositoryUrl: url,
        manifestBranch: branch,
        manifestFile: manifestFile,
        jobs: jobs,
        currentBranch: true,
        forceSync: true,
        resetFirst: clean,
        cleanFirst: clean,
        quiet: true
    ]

    if (targetDir) {
        repoParams.destinationDir = targetDir
    }
    if (reference) {
        repoParams.mirrorDir = reference
    }
    if (depth > 0) {
        repoParams.depth = depth
    }

    def syncTimeout = entry.timeout ?: 60
    timeout(time: syncTimeout, unit: 'MINUTES') {
        checkout(repoParams)
    }
}

