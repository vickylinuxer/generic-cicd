package com.linuxer.cicd.adapters.git

interface GitAdapter extends Serializable {
    /**
     * Get commit info (message, author, etc.)
     */
    Map getCommitInfo(def script, String commitSha)

    /**
     * Set build status on a commit (success, failure, pending)
     */
    void setBuildStatus(def script, String state, String description)

    /**
     * Add a comment to a pull request
     */
    void addPrComment(def script, String prId, String comment)
}
