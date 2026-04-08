package com.linuxer.cicd.adapters.issues

interface IssueAdapter extends Serializable {
    /**
     * Update issue status/state
     */
    void updateStatus(def script, String issueId, String status)

    /**
     * Add a comment to an issue
     */
    void addComment(def script, String issueId, String comment)
}
