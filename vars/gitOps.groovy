import com.tcs.cicd.Utils

/**
 * Git operations utility.
 *
 * Actions: clone, tag, push, branch, merge, stash, withAuth, log, info, submodule
 *
 * Usage:
 *   gitOps(action: 'clone', url: 'https://...', branch: 'main', dir: 'myrepo')
 *   gitOps(action: 'clone', url: 'https://...', shallow: true, depth: 1)
 *   gitOps(action: 'tag', tag: 'v1.0', message: 'Release 1.0', push: true)
 *   gitOps(action: 'push', remote: 'origin', branch: 'main')
 *   gitOps(action: 'branch', name: 'feature/x', from: 'main', checkout: true)
 *   gitOps(action: 'merge', source: 'feature/x', target: 'main')
 *   gitOps(action: 'stash', stashAction: 'save')
 *   gitOps(action: 'withAuth') { sh 'repo sync ...' }  // GIT_ASKPASS wrapper
 *   gitOps(action: 'log', format: '%B', count: 1)       // returns stdout
 *   gitOps(action: 'info', field: 'remoteUrl')           // returns git metadata
 *   gitOps(action: 'submodule')                          // update --init --recursive
 */
def call(Map params = [:]) {
    def action = params.action
    if (!action) {
        error "gitOps: 'action' required"
    }

    def credentialId = params.credentialId ?: params.config?.credentials?.git ?: params.config?.git?.credentialId ?: ''

    switch (action) {
        case 'clone':
            gitClone(params, credentialId)
            break
        case 'tag':
            gitTag(params, credentialId)
            break
        case 'push':
            gitPush(params, credentialId)
            break
        case 'branch':
            gitBranch(params)
            break
        case 'merge':
            gitMerge(params)
            break
        case 'stash':
            gitStash(params)
            break
        case 'withAuth':
            withGitAuth(credentialId, params.body)
            break
        case 'log':
            return gitLog(params)
        case 'info':
            return gitInfo(params)
        case 'submodule':
            gitSubmodule(params)
            break
        default:
            error "gitOps: unknown action '${action}'"
    }
}

/**
 * Clone via Jenkins GitSCM — no shell git clone, no .netrc.
 */
private void gitClone(Map params, String credentialId) {
    def url = params.url ?: error("gitOps clone: 'url' required")
    def branch = params.branch ?: 'main'
    def targetDir = params.dir ?: ''
    def extensions = []

    if (params.clean) {
        extensions << [$class: 'CleanBeforeCheckout']
    }
    // Build a single CloneOption if any clone options are needed
    def cloneOpts = [:]
    if (params.shallow) {
        cloneOpts.shallow = true
        cloneOpts.depth = params.depth ?: 1
    }
    if (params.reference) {
        cloneOpts.reference = params.reference
    }
    if (cloneOpts) {
        extensions << ([$class: 'CloneOption'] + cloneOpts)
    }

    def body = {
        checkout([$class: 'GitSCM',
            branches: [[name: "*/${branch}"]],
            userRemoteConfigs: [[url: url, credentialsId: credentialId]],
            extensions: extensions
        ])
    }

    targetDir ? dir(targetDir) { body() } : body()
}

/**
 * GIT_ASKPASS credential wrapper for shell git operations.
 * Used by gitOps internally and exposed via action: 'withAuth' for
 * other Ops files (scmOps, etc.) that need authenticated shell git.
 */
private void withGitAuth(String credentialId, Closure body) {
    withCredentials([usernamePassword(credentialsId: credentialId,
        usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
        def askpassFile = sh(script: 'mktemp "${WORKSPACE}/.git-askpass-XXXXXX.sh"',
            returnStdout: true).trim()
        try {
            writeFile file: askpassFile, text: '#!/bin/sh\necho $GIT_PASS'
            sh "chmod 700 '${askpassFile}'"
            withEnv(["GIT_ASKPASS=${askpassFile}"]) {
                body()
            }
        } finally {
            sh "rm -f '${askpassFile}'"
        }
    }
}

private void gitTag(Map params, String credentialId) {
    def tag = params.tag ?: error("gitOps tag: 'tag' required")
    def message = params.message ?: "Tag ${tag}"
    def pushTag = params.push ?: false

    def escTag = Utils.shellEscape(tag)
    def escMessage = Utils.shellEscape(message)

    sh "git tag -a '${escTag}' -m '${escMessage}'"

    if (pushTag) {
        withGitAuth(credentialId) {
            sh "git push origin '${escTag}'"
        }
    }
}

private void gitPush(Map params, String credentialId) {
    def remote = params.remote ?: 'origin'
    def branch = params.branch ?: ''
    def force = params.force ?: false

    def forceFlag = force ? '--force' : ''
    def branchArg = branch ?: ''

    withGitAuth(credentialId) {
        sh "git push ${forceFlag} ${remote} ${branchArg}".trim()
    }
}

private void gitBranch(Map params) {
    def name = params.name ?: error("gitOps branch: 'name' required")
    def from = params.from ?: ''
    def doCheckout = params.checkout ?: false

    def escName = Utils.shellEscape(name)
    def escFrom = from ? Utils.shellEscape(from) : ''
    def fromArg = escFrom ? "'${escFrom}'" : ''

    sh """#!/bin/bash
set -e
git branch '${escName}' ${fromArg}
${doCheckout ? "git checkout '${escName}'" : ''}
"""
}

private void gitMerge(Map params) {
    def source = params.source ?: error("gitOps merge: 'source' required")
    def target = params.target ?: ''
    def message = params.message ?: "Merge ${source}"

    def escSource = Utils.shellEscape(source)
    def escTarget = target ? Utils.shellEscape(target) : ''
    def escMessage = Utils.shellEscape(message)

    sh """#!/bin/bash
set -e
${escTarget ? "git checkout '${escTarget}'" : ''}
git merge '${escSource}' -m '${escMessage}'
"""
}

private void gitStash(Map params) {
    def stashAction = params.stashAction ?: 'save'

    switch (stashAction) {
        case 'save':
            sh "git stash"
            break
        case 'pop':
            sh "git stash pop"
            break
        case 'apply':
            sh "git stash apply"
            break
        case 'drop':
            sh "git stash drop"
            break
        default:
            error "gitOps stash: unknown stashAction '${stashAction}'"
    }
}

/**
 * Read git log output.
 * Returns the stdout string.
 */
private String gitLog(Map params) {
    def format = params.format ?: '%B'
    def count = params.count ?: 1
    return sh(script: "git log -${count} --pretty=${format}", returnStdout: true).trim()
}

/**
 * Read git metadata.
 * Fields: remoteUrl, commitSha, branch
 */
private String gitInfo(Map params) {
    def field = params.field ?: error("gitOps info: 'field' required")

    switch (field) {
        case 'remoteUrl':
            return sh(script: 'git remote get-url origin', returnStdout: true).trim()
        case 'commitSha':
            return sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
        case 'branch':
            return sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
        default:
            error "gitOps info: unknown field '${field}'"
    }
}

/**
 * Update git submodules.
 */
private void gitSubmodule(Map params) {
    def init = params.init != false
    def recursive = params.recursive != false
    def initFlag = init ? '--init' : ''
    def recursiveFlag = recursive ? '--recursive' : ''
    sh "git submodule update ${initFlag} ${recursiveFlag}".trim()
}
