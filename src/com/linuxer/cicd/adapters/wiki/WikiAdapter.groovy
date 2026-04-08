package com.linuxer.cicd.adapters.wiki

interface WikiAdapter extends Serializable {
    /**
     * Create a new page in the wiki
     */
    void createPage(def script, String title, String content)

    /**
     * Update an existing page
     */
    void updatePage(def script, String pageId, String title, String content)
}
