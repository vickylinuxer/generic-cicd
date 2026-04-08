def call(Map params = [:]) {
    def image = params.image
    def tag = params.tag ?: 'latest'
    def args = params.args ?: ''
    def command = params.command ?: ''
    def dockerfile = params.dockerfile ?: 'Dockerfile'
    def context = params.context ?: '.'
    def buildArgs = params.buildArgs ?: [:]
    def action = params.action ?: 'run'

    switch (action) {
        case 'build':
            dockerBuildImage(image, tag, dockerfile, context, buildArgs)
            break
        case 'run':
            dockerRunContainer(image, tag, args, command)
            break
        case 'push':
            // Push is handled by publishImage via ArtifactAdapter
            echo "Use publishImage step for pushing Docker images"
            break
        default:
            error "Unknown docker action: ${action}"
    }
}

private void dockerBuildImage(String image, String tag, String dockerfile, String context, Map buildArgs) {
    def buildArgStr = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')

    echo "Building Docker image: ${image}:${tag}"
    sh """#!/bin/bash
        set -e
        docker build \
            -f ${dockerfile} \
            -t ${image}:${tag} \
            -t ${image}:latest \
            ${buildArgStr} \
            ${context}
    """
    echo "Docker image built: ${image}:${tag}"
}

private void dockerRunContainer(String image, String tag, String args, String command) {
    echo "Running Docker container: ${image}:${tag}"
    sh """#!/bin/bash
        set -e
        docker run --rm ${args} ${image}:${tag} ${command}
    """
}
