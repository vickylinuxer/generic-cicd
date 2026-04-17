package com.tcs.cicd

import com.cloudbees.groovy.cps.NonCPS

class Utils implements Serializable {

    /**
     * Escape a string for safe use inside single-quoted shell arguments.
     * Replaces ' with '\'' (end quote, escaped quote, start quote).
     */
    @NonCPS
    static String shellEscape(String s) {
        return s?.replaceAll("'", "'\\\\''") ?: ''
    }

    /**
     * Check available disk space at path, fail build if below threshold.
     * @param script Pipeline script context
     * @param path Directory to check (default: WORKSPACE)
     * @param minGB Minimum required GB (default: 10)
     */
    static void checkDiskSpace(def script, String path = '', int minGB = 10) {
        def checkPath = path ?: script.env.WORKSPACE ?: '.'
        def availKB = script.sh(script: "df -k '${shellEscape(checkPath)}' | tail -1 | awk '{print \$4}'",
            returnStdout: true).trim()
        def availGB = 0
        try {
            availGB = (availKB as long) / (1024 * 1024)
        } catch (Exception ignored) {
            script.echo "WARNING: Could not parse disk space for ${checkPath}: ${availKB}"
            return
        }
        script.echo "Disk space at ${checkPath}: ${String.format('%.1f', availGB)} GB available (minimum: ${minGB} GB)"
        if (availGB < minGB) {
            script.error "Insufficient disk space at ${checkPath}: ${String.format('%.1f', availGB)} GB available, ${minGB} GB required"
        }
    }

    /**
     * Set git user.name and user.email from project config.
     * Reads environment.git.userName and environment.git.userEmail.
     * Required by repo tool (AOSP) and useful for any git operations inside Docker.
     */
    static void setupGitConfig(def script, Map config) {
        def userName = config.environment?.git?.userName ?: ''
        def userEmail = config.environment?.git?.userEmail ?: ''
        if (userName) {
            script.sh "git config --global user.name '${shellEscape(userName)}'"
        }
        if (userEmail) {
            script.sh "git config --global user.email '${shellEscape(userEmail)}'"
        }
    }

    /**
     * Retry a closure up to maxRetries times with exponential backoff.
     * Rethrows the last exception if all retries fail.
     */
    static Object withRetry(def script, int maxRetries = 2, Closure body) {
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return body()
            } catch (Exception e) {
                if (i == maxRetries) throw e
                script.echo "Retry ${i + 1}/${maxRetries}: ${e.message}"
                script.sleep(5 * (i + 1))
            }
        }
    }
}
