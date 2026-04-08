def call(Map params = [:]) {
    def config = params.config ?: [:]

    // Checkout is handled by Jenkins SCM in most cases.
    // This step handles additional checkout needs: submodules, repo tool, etc.
    echo "Checking out source code..."

    checkout scm

    // Handle git submodules if present
    if (fileExists('.gitmodules')) {
        echo "Initializing git submodules..."
        sh 'git submodule update --init --recursive'
    }

    // Handle Android repo manifest if present
    if (fileExists('.repo')) {
        def aosp = config.aosp ?: [:]
        def jobs = aosp.jobs ?: 8
        echo "Syncing repo manifest..."
        sh "repo sync -j${jobs} --optimized-fetch --force-sync"
    }

    // Set GIT_COMMIT and GIT_BRANCH env vars if not already set
    if (!env.GIT_COMMIT) {
        env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    }
    if (!env.GIT_BRANCH) {
        env.GIT_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
    }

    echo "Branch: ${env.GIT_BRANCH} | Commit: ${env.GIT_COMMIT.take(7)}"
}
