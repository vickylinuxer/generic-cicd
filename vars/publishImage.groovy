def call(Map params = [:]) {
    def config = params.config ?: [:]
    def platforms = params.platforms
    def imageConfig = config.publishImage ?: config.docker ?: params

    def adapter = platforms?.getArtifactAdapter()
    if (!adapter) {
        echo "No artifact adapter configured, skipping image push"
        return
    }

    def imageName = imageConfig.imageName ?: config.project?.name ?: env.JOB_BASE_NAME
    def tag = imageConfig.tag ?: env.BUILD_NUMBER

    echo "Publishing Docker image: ${imageName}:${tag}"
    adapter.pushImage(this, imageName, tag)
}
