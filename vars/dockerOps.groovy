import com.tcs.cicd.Utils

/**
 * Docker operations utility — Jenkins Docker DSL.
 *
 * Actions: build, push, pull, tag, run, inside, inspect
 *
 * Usage:
 *   dockerOps(action: 'build', image: 'myapp', tag: '1.0', context: '.')
 *   dockerOps(action: 'push', image: 'myapp', tag: '1.0', registry: '<registry-url>', credentialId: 'docker-creds')
 *   dockerOps(action: 'pull', image: 'myapp', tag: '1.0')
 *   dockerOps(action: 'tag', image: 'myapp:1.0', targetTag: 'latest')
 *   dockerOps(action: 'run', image: 'myapp', tag: '1.0', args: '-p 8080:80', command: 'serve')
 *   dockerOps(action: 'inside', image: 'myapp', tag: '1.0', args: '-v ...') { sh 'make' }
 *   dockerOps(action: 'inspect', image: 'myapp', tag: '1.0')
 */
def call(Map params = [:]) {
    def action = params.action ?: 'run'

    switch (action) {
        case 'build':
            return dockerBuildImage(params)
        case 'push':
            dockerPushImage(params)
            break
        case 'pull':
            dockerPullImage(params)
            break
        case 'tag':
            dockerTagImage(params)
            break
        case 'run':
            dockerRunContainer(params)
            break
        case 'inside':
            dockerInside(params)
            break
        case 'inspect':
            return dockerInspect(params)
        default:
            error "dockerOps: unknown action '${action}'"
    }
}

private Object dockerBuildImage(Map params) {
    def image = params.image ?: error("dockerOps build: 'image' required")
    def tag = params.tag ?: 'latest'
    def dockerfile = params.dockerfile ?: 'Dockerfile'
    def context = params.context ?: '.'
    def buildArgs = params.buildArgs ?: [:]
    def cacheFrom = params.cacheFrom ?: ''

    def buildArgStr = buildArgs.collect { k, v ->
        def escK = Utils.shellEscape(k as String)
        def escV = Utils.shellEscape(v as String)
        "--build-arg '${escK}=${escV}'"
    }.join(' ')

    // Pull cache image if specified (best-effort, ignore pull failures)
    def cacheStr = ''
    if (cacheFrom) {
        try {
            docker.image(cacheFrom).pull()
            cacheStr = "--cache-from ${cacheFrom}"
        } catch (Exception e) {
            echo "Cache image not available (${cacheFrom}), building without cache"
        }
    }

    def buildOpts = "-f ${dockerfile} ${cacheStr} ${buildArgStr} ${context}".trim()

    echo "Building Docker image: ${image}:${tag}"
    def img = docker.build("${image}:${tag}", buildOpts)
    echo "Docker image built: ${image}:${tag}"
    return img
}

private void dockerPushImage(Map params) {
    def image = params.image ?: error("dockerOps push: 'image' required")
    def tag = params.tag ?: 'latest'
    def registry = params.registry ?: ''
    def credentialId = params.credentialId ?: ''
    if (!credentialId) { error "dockerOps push: 'credentialId' required (set credentials.docker in config or pass credentialId)" }

    echo "Pushing Docker image: ${image}:${tag}"
    docker.withRegistry(registry, credentialId) {
        docker.image("${image}:${tag}").push()
    }
}

private void dockerPullImage(Map params) {
    def image = params.image ?: error("dockerOps pull: 'image' required")
    def tag = params.tag ?: 'latest'

    echo "Pulling Docker image: ${image}:${tag}"
    docker.image("${image}:${tag}").pull()
}

private void dockerTagImage(Map params) {
    def image = params.image ?: error("dockerOps tag: 'image' required (format: name:tag)")
    def targetTag = params.targetTag ?: 'latest'
    def registry = params.registry ?: ''
    def credentialId = params.credentialId ?: ''
    if (!credentialId) { error "dockerOps tag: 'credentialId' required (set credentials.docker in config or pass credentialId)" }

    echo "Tagging ${image} as ${targetTag}"
    docker.withRegistry(registry, credentialId) {
        docker.image(image).push(targetTag)
    }
}

private void dockerRunContainer(Map params) {
    def image = params.image ?: error("dockerOps run: 'image' required")
    def tag = params.tag ?: 'latest'
    def args = params.args ?: ''
    def command = params.command ?: ''

    echo "Running Docker container: ${image}:${tag}"
    docker.image("${image}:${tag}").inside(args) {
        sh command
    }
}

/**
 * Run a closure inside a Docker container (orchestration wrapper).
 * Unlike 'run' which executes a single command, 'inside' wraps pipeline steps.
 */
private void dockerInside(Map params) {
    def image = params.image ?: error("dockerOps inside: 'image' required")
    def args = params.args ?: ''
    def body = params.body ?: error("dockerOps inside: 'body' closure required")

    // Support full image refs (e.g., 'myapp:1.0') or separate image+tag
    def imageRef = params.containsKey('tag') && params.tag ? "${image}:${params.tag}" : image

    docker.image(imageRef).inside(args) {
        body()
    }
}

private String dockerInspect(Map params) {
    def image = params.image ?: error("dockerOps inspect: 'image' required")
    def tag = params.tag ?: 'latest'

    return sh(script: "docker inspect ${image}:${tag}", returnStdout: true).trim()
}
