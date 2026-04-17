import com.tcs.cicd.ConfigLoader
import com.tcs.cicd.PlatformFactory
import com.tcs.cicd.Utils
import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified pipeline entry point.
 *
 * Modes:
 *   - Project mode (no configRepo): checkout scm, load cicd.yml from workspace.
 *     Usage: pipeliner() or ciPipeline()
 *
 *   - Config repo mode (with configRepo): clone external config repo, load YAML configs.
 *     Sub-modes: integration (manifest sync + parallel builds) or component (single project).
 *     Usage: pipeliner(config: 'integration', configRepo: '...', credentials: '...')
 */
def call(Map params = [:]) {
    if (params.configRepo) {
        runConfigRepoMode(params)
    } else {
        runProjectMode(params)
    }
}

// ── Project mode (single-project, config in repo) ───────────────────

private void runProjectMode(Map params) {
    def configFilePath = params.remove('configFile')
    def platformsFilePath = params.remove('platformsFile')
    def externalStagesDir = params.remove('_stagesDir')
    def agentLabel = params.environment?.agent ?: env.CICD_AGENT ?: 'any'

    node(agentLabel == 'any' ? '' : agentLabel) {
        stage('Checkout') {
            checkout scm
            if (fileExists('.gitmodules')) {
                gitOps(action: 'submodule')
            }
            echo "Branch: ${env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'unknown'} | Commit: ${(env.GIT_COMMIT ?: 'unknown').take(7)}"
        }

        def config = new ConfigLoader(this).load(params, configFilePath, platformsFilePath)
        if (externalStagesDir) { config._stagesDir = externalStagesDir }

        def buildTypeConfig = config._buildTypeConfig ?: [:]
        def platforms = new PlatformFactory(this, config)
        def dockerImage = config.environment?.docker?.image ?: ''
        def stages = (config.stages ?: []).findAll { it != 'checkout' }
        def stageTimings = []
        def pipelineStartTime = System.currentTimeMillis()

        def runPipeline = {
            try {
                timeout(time: config.environment?.timeout ?: 60, unit: 'MINUTES') {
                    stage('Initialize') {
                        applyProperties(buildTypeConfig)
                        initializeBuild(config, buildTypeConfig)
                        Utils.checkDiskSpace(this, env.WORKSPACE, (config.environment?.minDiskGB ?: 10) as int)
                        Utils.setupGitConfig(this, config)
                    }

                    if (config.matrix) {
                        runMatrix(config, platforms, stages, stageTimings)
                    } else {
                        stages.each { stageEntry ->
                            runStage(stageEntry, config, platforms, '', stageTimings)
                        }
                    }
                }

                // Post: success
                if (shouldNotify(buildTypeConfig, 'success') || shouldNotify(buildTypeConfig, 'fixed')) {
                    notifyBuildStatus(config, platforms, 'SUCCESS')
                }
                if (buildTypeConfig.tagOnSuccess) { tagBuild(config) }

            } catch (Exception e) {
                currentBuild.result = 'FAILURE'
                if (shouldNotify(buildTypeConfig, 'failure')) {
                    notifyBuildStatus(config, platforms, 'FAILURE')
                }
                throw e
            } finally {
                collectMetrics(config, stageTimings)
                def totalDuration = (System.currentTimeMillis() - pipelineStartTime) / 1000
                echo "=== Build Summary ==="
                echo "Result: ${currentBuild.currentResult ?: 'SUCCESS'}"
                echo "Duration: ${totalDuration}s"
                echo "Stages: ${stages.join(', ')}"
                echo "===================="
            }
        }

        if (dockerImage) {
            def dockerArgs = config.environment?.docker?.args ?: ''
            dockerOps(action: 'inside', image: dockerImage, args: dockerArgs, body: { runPipeline() })
        } else {
            runPipeline()
        }
    }
}

private void applyProperties(Map buildTypeConfig) {
    def properties = []

    def keepBuilds = buildTypeConfig.keepBuilds
    if (keepBuilds && keepBuilds > 0) {
        properties.add(buildDiscarder(logRotator(numToKeepStr: keepBuilds.toString())))
    }

    def paramDefs = buildTypeConfig.parameters?.collect { param ->
        switch (param.type) {
            case 'string':  return string(name: param.name, defaultValue: param.default ?: '', description: param.description ?: '')
            case 'boolean': return booleanParam(name: param.name, defaultValue: param.default ?: false, description: param.description ?: '')
            case 'choice':  return choice(name: param.name, choices: param.choices ?: [], description: param.description ?: '')
            default: return null
        }
    }?.findAll { it != null }
    if (paramDefs) { properties.add(parameters(paramDefs)) }

    def trigger = buildTypeConfig.trigger
    if (trigger && trigger != 'webhook' && trigger != 'manual') {
        properties.add(pipelineTriggers([cron(trigger)]))
    }

    if (properties) { properties(properties) }
}

private void initializeBuild(Map config, Map buildTypeConfig) {
    def buildType = config.project?.buildType ?: 'ci'
    def projectName = config.project?.name ?: env.JOB_BASE_NAME

    currentBuild.displayName = "#${env.BUILD_NUMBER} [${buildType}]"
    echo "Project: ${projectName} | Build Type: ${buildType}"
    echo "Agent: ${config.environment?.agent ?: 'any'}"
    echo "Stages: ${config.stages?.join(' → ')}"

    if (config.matrix) {
        def matrixKey = config.matrix.keySet().first()
        echo "Matrix: ${matrixKey} = ${config.matrix[matrixKey]}"
    }

    if (buildTypeConfig.cleanWorkspace) { cleanWs() }
}

private boolean shouldNotify(Map buildTypeConfig, String event) {
    def notifyOn = buildTypeConfig.notifyOn ?: ['failure']
    if (notifyOn.contains('always')) return true
    if (notifyOn.contains(event)) return true
    if (event == 'fixed' && notifyOn.contains('fixed')) {
        return currentBuild.previousBuild?.result == 'FAILURE'
    }
    return false
}

// ── Matrix execution ────────────────────────────────────────────────

private void runMatrix(Map config, PlatformFactory platforms, List stages, List stageTimings) {
    def matrixKey = config.matrix.keySet().first()
    def matrixValues = config.matrix[matrixKey]
    def preStages = config.matrix?.preStages ?: ['checkout', 'fetchSources']
    def postStages = config.matrix?.postStages ?: ['notify']

    def preMatrixStages = stages.findAll { it in preStages }
    def postMatrixStages = stages.findAll { it in postStages }
    def matrixStages = stages.findAll { !(it in preStages || it in postStages) }

    preMatrixStages.each { runStage(it, config, platforms, '', stageTimings) }

    if (matrixStages) {
        echo "Matrix fan-out: ${matrixKey} = ${matrixValues}"
        def branches = [:]
        for (value in matrixValues) {
            def matrixValue = value
            branches["${matrixKey}: ${matrixValue}"] = {
                def branchConfig = ConfigLoader.deepMerge(config, [:])
                def projectType = branchConfig.project?.type
                if (projectType && branchConfig[projectType] instanceof Map) {
                    branchConfig[projectType][matrixKey] = matrixValue
                }
                branchConfig._matrix = (branchConfig._matrix ?: [:]) + [(matrixKey): matrixValue]
                branchConfig.environment = branchConfig.environment ?: [:]
                branchConfig.environment["MATRIX_${matrixKey.toUpperCase()}"] = matrixValue

                matrixStages.each { runStage(it, branchConfig, platforms, '', stageTimings) }
            }
        }
        parallel(branches)
    }

    postMatrixStages.each { runStage(it, config, platforms, '', stageTimings) }
}

// ── Build metrics ───────────────────────────────────────────────────

private void collectMetrics(Map config, List stageTimings) {
    def metrics = [
        jobName    : env.JOB_NAME,
        buildNumber: env.BUILD_NUMBER?.toInteger(),
        result     : currentBuild.currentResult,
        duration   : currentBuild.duration,
        startTime  : currentBuild.startTimeInMillis,
        agent      : env.NODE_NAME ?: 'unknown',
        branch     : env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'N/A',
        commit     : env.GIT_COMMIT ?: 'N/A',
        buildUrl   : env.BUILD_URL,
        timestamp  : System.currentTimeMillis()
    ]

    if (config) {
        metrics.projectName = config.project?.name
        metrics.projectType = config.project?.type
        metrics.buildType = config.project?.buildType
    }

    if (stageTimings) {
        metrics.stages = stageTimings.collect { [name: it.name, duration: it.duration, result: it.result] }
    }

    def json = JsonOutput.prettyPrint(JsonOutput.toJson(metrics))
    writeFile file: 'build-metrics.json', text: json
    archiveArtifacts artifacts: 'build-metrics.json', allowEmptyArchive: true

    def metricsConfig = config?.metrics ?: [:]
    if (metricsConfig.export && metricsConfig.endpoint) {
        try {
            def requestParams = [
                url: metricsConfig.endpoint, httpMode: 'POST',
                contentType: 'APPLICATION_JSON', requestBody: json,
                validResponseCodes: '200:299', timeout: 15, quiet: true
            ]
            if (metricsConfig.credentialId) { requestParams.authentication = metricsConfig.credentialId }
            httpRequest(requestParams)
            echo "Metrics exported to ${metricsConfig.endpoint}"
        } catch (Exception e) {
            echo "WARNING: Failed to export metrics: ${e.message}"
        }
    }
}

private void tagBuild(Map config) {
    def version = params.VERSION ?: env.BUILD_NUMBER
    def tag = "v${version}"
    def credentialId = config.credentials?.git ?: config.git?.credentialId ?: ''
    try {
        gitOps(action: 'tag', tag: tag,
            message: "Release ${tag} (build #${env.BUILD_NUMBER})",
            push: true, credentialId: credentialId)
        echo "Tagged build as ${tag}"
    } catch (Exception e) {
        echo "WARNING: Failed to tag build: ${e.message}"
    }
}

// ── Config repo mode (multi-project, external config) ───────────────

private void runConfigRepoMode(Map params) {
    def configName = params.config ?: error("'config' parameter required")
    def configRepoUrl = params.configRepo ?: error("'configRepo' parameter required")
    def configBranch = params.configBranch ?: 'main'
    def credentialsId = params.credentials ?: ''
    if (!credentialsId) { error "pipeliner: 'credentials' parameter required (Jenkins credential ID for git)" }
    def agentLabel = params.agent ?: 'any'

    node(agentLabel == 'any' ? '' : agentLabel) {
        def configDir

        stage('Load Config') {
            gitOps(action: 'clone', url: configRepoUrl, branch: configBranch,
                   dir: '_config', credentialId: credentialsId)
            configDir = "${pwd()}/_config"
        }

        def configFile = "${configDir}/projects/${configName}.yml"
        if (!fileExists(configFile)) {
            error("Config not found: projects/${configName}.yml")
        }

        def pipelineConfig = readYaml(file: configFile)
        def mode = pipelineConfig.mode ?: 'component'

        ConfigLoader.validatePipelinerConfig(this, pipelineConfig)

        // Dry-run mode: validate configs without building
        if (params.DRY_RUN == 'true' || pipelineConfig.dryRun == true) {
            echo "=== DRY RUN MODE ==="
            dryRun(pipelineConfig, configDir, mode, credentialsId)
            return
        }

        if (mode == 'integration') {
            runIntegration(pipelineConfig, configDir, credentialsId, configRepoUrl, configBranch)
        } else {
            runComponent(pipelineConfig, configDir, configName, credentialsId, configRepoUrl, configBranch)
        }

        // Workspace cleanup for intermediate build artifacts
        def cleanup = pipelineConfig.cleanup ?: [:]
        if (cleanup.afterBuild && currentBuild.currentResult == 'SUCCESS') {
            cleanupWorkspace(pipelineConfig.workspace ?: env.WORKSPACE, cleanup)
        }
    }
}

// ── Integration mode ────────────────────────────────────────────────

private void runIntegration(Map pipelineConfig, String configDir, String credentialsId, String configRepoUrl, String configBranch) {
    def manifestUrl = pipelineConfig.manifest?.url
    def manifestBranch = pipelineConfig.manifest?.branch ?: 'main'
    def manifestFile = pipelineConfig.manifest?.file ?: ''
    def manifestJobs = pipelineConfig.manifest?.jobs ?: 4
    def syncTimeout = pipelineConfig.manifest?.timeout ?: 30
    def subsystems = pipelineConfig.subsystems ?: []
    def workspace = pipelineConfig.workspace ?: "/var/jenkins/workspace/integration"

    def manifestReference = pipelineConfig.manifest?.reference ?: ''

    def cleanWs = pipelineConfig.cleanWorkspace ?: false
    def minDiskGB = pipelineConfig.environment?.minDiskGB ?: 10

    if (cleanWs) {
        stage('Clean Workspace') {
            echo "Wiping workspace: ${workspace}"
            dir(workspace) { deleteDir() }
        }
    }

    Utils.checkDiskSpace(this, workspace, minDiskGB as int)

    stage('Sync Sources') {
        timeout(time: syncTimeout, unit: 'MINUTES') {
            scmOps(config: [checkout: [manifest: [
                [type: 'repo', name: 'manifest', url: manifestUrl,
                 branch: manifestBranch, dir: workspace,
                 file: manifestFile, reference: manifestReference,
                 jobs: manifestJobs, credentialId: credentialsId,
                 clean: cleanWs]
            ]]])
        }

        // Run post-sync script if configured (path relative to workspace)
        def postSyncScript = pipelineConfig.manifest?.postSyncScript ?: ''
        if (postSyncScript) {
            sh "bash '${resolveRelativePath(postSyncScript, workspace)}'"
        }

        if (!fileExists("${workspace}/build-scripts")) {
            echo "WARNING: ${workspace}/build-scripts is missing after sync. Ensure manifest includes build-scripts or set buildScripts.url for component mode."
        }
    }

    def subsystemResults = new ConcurrentHashMap<String, String>()
    def subsystemTimings = new ConcurrentHashMap<String, Long>()
    def globalTimeout = pipelineConfig.environment?.timeout ?: 0

    def builds = [failFast: pipelineConfig.failFast != null ? pipelineConfig.failFast : false]
    for (s in subsystems) {
        def subsystemName = s
        builds[subsystemName] = {
            def cfgFile = "${configDir}/projects/${subsystemName}.yml"
            def cfg = fileExists(cfgFile) ? readYaml(file: cfgFile) : [:]
            def agent = cfg.environment?.agent ?: ''
            def subTimeout = cfg.environment?.timeout ?: globalTimeout ?: 120

            def startTime = System.currentTimeMillis()
            try {
                node(agent) {
                    timeout(time: subTimeout, unit: 'MINUTES') {
                        def agentConfigDir = "${workspace}/_config-${subsystemName}"
                        gitOps(action: 'clone', url: configRepoUrl, branch: configBranch,
                               dir: agentConfigDir, credentialId: credentialsId)
                        buildSubsystem(subsystemName, agentConfigDir, workspace)
                    }
                }
                subsystemResults.put(subsystemName, 'SUCCESS')
            } catch (Exception e) {
                subsystemResults.put(subsystemName, 'FAILED')
                throw e
            } finally {
                subsystemTimings.put(subsystemName, (System.currentTimeMillis() - startTime) / 1000 as long)
            }
        }
    }
    parallel builds

    // Log build summary table
    echo "=== Subsystem Build Summary ==="
    echo String.format("%-30s %-10s %s", "Subsystem", "Result", "Duration")
    echo "-".multiply(60)
    for (s in subsystems) {
        def result = subsystemResults.get(s) ?: 'UNKNOWN'
        def duration = subsystemTimings.get(s) ?: 0
        echo String.format("%-30s %-10s %ds", s, result, duration)
    }
    echo "================================"
}

// ── Component mode ──────────────────────────────────────────────────

private void runComponent(Map pipelineConfig, String configDir, String configName, String credentialsId, String configRepoUrl, String configBranch) {
    def projectName = pipelineConfig.project?.name ?: configName
    def repoUrl = pipelineConfig.project?.repoUrl ?: error("'project.repoUrl' required in config")
    def repoBranch = pipelineConfig.project?.branch ?: 'main'
    def workspace = pipelineConfig.workspace ?: "/var/jenkins/workspace/component"
    def buildScripts = pipelineConfig.buildScripts ?: [:]
    def componentAgent = pipelineConfig.environment?.agent ?: ''
    def componentTimeout = pipelineConfig.environment?.timeout ?: 120

    def doComponent = {
        def minDiskGB = pipelineConfig.environment?.minDiskGB ?: 10
        Utils.checkDiskSpace(this, workspace, minDiskGB as int)

        def agentConfigDir = "${workspace}/_config"
        stage('Load Config on Agent') {
            gitOps(action: 'clone', url: configRepoUrl, branch: configBranch,
                   dir: agentConfigDir, credentialId: credentialsId)
        }

        stage('Checkout') {
            gitOps(action: 'clone', url: repoUrl, branch: repoBranch,
                   dir: "${workspace}/subsystems/${projectName}",
                   credentialId: credentialsId)
        }

        stage('Prepare Build Scripts') {
            if (buildScripts.url) {
                def bsBranch = buildScripts.branch ?: 'main'
                def bsCred = buildScripts.credentialId ?: credentialsId
                gitOps(action: 'clone', url: buildScripts.url, branch: bsBranch,
                       dir: "${workspace}/build-scripts", credentialId: bsCred, clean: true)
            } else if (!fileExists("${workspace}/build-scripts")) {
                echo "WARNING: ${workspace}/build-scripts not found. Component builds using build-scripts/* paths may fail."
            }
        }

        buildSubsystem(projectName, agentConfigDir, workspace)
    }

    if (componentAgent) {
        node(componentAgent) {
            timeout(time: componentTimeout, unit: 'MINUTES') { doComponent() }
        }
    } else {
        doComponent()
    }
}

// ── Shared build logic ──────────────────────────────────────────────

private void buildSubsystem(String subsystemName, String configDir, String workspace) {
    dir("${workspace}/subsystems/${subsystemName}") {
        def cfgFile = "${configDir}/projects/${subsystemName}.yml"
        def config = new ConfigLoader(this).load([:], cfgFile)
        def platforms = new PlatformFactory(this, config)

        // Extract issue ID from branch name
        def branchName = env.GIT_BRANCH ?: env.BRANCH_NAME ?: ''
        def issueId = extractIssueId(branchName, config.branchStrategy?.issuePattern ?: '([A-Z]+-\\d+)')
        if (issueId) {
            config._issueId = issueId
            echo "Detected issue: ${issueId}"
        }

        // Resolve relative paths in config to absolute workspace paths
        resolveConfigPaths(config, workspace)

        def stages = (config.stages ?: ['build']).findAll { it != 'checkout' }
        def dockerImage = config.environment?.docker?.image ?: ''
        def dockerArgs = config.environment?.docker?.args ?: ''

        // Auto-add workspace volume mount for Docker containers
        if (dockerImage && !dockerArgs.contains("${workspace}:${workspace}")) {
            dockerArgs = "${dockerArgs} -v ${workspace}:${workspace}".trim()
        }

        // Ensure build-scripts are accessible inside Docker containers
        if (dockerImage && !dockerArgs.contains("${workspace}/build-scripts")) {
            dockerArgs = "${dockerArgs} -v ${workspace}/build-scripts:${workspace}/build-scripts:ro".trim()
        }

        // Auto-inject HOME so Docker containers have a writable home directory
        if (dockerImage && !dockerArgs.contains('-e HOME=')) {
            dockerArgs = "${dockerArgs} -e HOME=${workspace}".trim()
        }

        def executeStages = {
            Utils.setupGitConfig(this, config)
            withEnv(["WORKSPACE_ROOT=${workspace}", "WORKSPACE=${workspace}"]) {
                stages.each { stageEntry ->
                    runStage(stageEntry, config, platforms, subsystemName)
                }
            }
        }

        if (dockerImage) {
            def dockerCred = config.environment?.docker?.credentialId ?: ''
            def registryUrl = ''
            if (dockerImage.contains('/') && dockerImage.split('/')[0].contains('.')) {
                registryUrl = "https://${dockerImage.split('/')[0]}"
            }
            def body = {
                dockerOps(action: 'inside', image: dockerImage, args: dockerArgs, body: {
                    executeStages()
                })
            }
            if (dockerCred && registryUrl) {
                docker.withRegistry(registryUrl, dockerCred) { body() }
            } else {
                body()
            }
        } else {
            executeStages()
        }
    }
}

// ── Path resolution ─────────────────────────────────────────────────

private String resolveRelativePath(String path, String workspace) {
    if (!path) return path
    return path.startsWith('/') ? path : "${workspace}/${path}"
}

private void resolveConfigPaths(Map config, String workspace) {
    // Resolve buildScript for whichever builder type is configured
    def builderType = config.project?.type ?: 'custom'
    if (config[builderType]?.buildScript) {
        config[builderType].buildScript = resolveRelativePath(config[builderType].buildScript, workspace)
    }

    // Resolve cleanup paths
    if (config.cleanup instanceof Map) {
        (config.cleanup.dirs ?: []).each { entry ->
            if (entry.path) {
                entry.path = resolveRelativePath(entry.path, workspace)
            }
        }
    }
}

// ── Dry-run mode ────────────────────────────────────────────────────

private void dryRun(Map pipelineConfig, String configDir, String mode, String credentialsId) {
    echo "Mode: ${mode}"
    echo "Workspace: ${pipelineConfig.workspace ?: "/var/jenkins/workspace/${mode}"}"

    if (mode == 'integration') {
        echo "Manifest URL: ${pipelineConfig.manifest?.url ?: 'NOT SET'}"
        def subsystems = pipelineConfig.subsystems ?: []
        echo "Subsystems (${subsystems.size()}): ${subsystems.join(', ')}"

        if (!pipelineConfig.manifest?.url) {
            echo "ERROR: manifest.url is required for integration mode"
        }
        if (!subsystems) {
            echo "ERROR: subsystems list is empty"
        }

        subsystems.each { sub ->
            def cfgFile = "${configDir}/projects/${sub}.yml"
            if (fileExists(cfgFile)) {
                def cfg = readYaml(file: cfgFile)
                echo "  [OK] ${sub}: type=${cfg.project?.type ?: 'custom'}, docker=${cfg.environment?.docker?.image ?: 'none'}"
                validateSubsystemConfig(sub, cfg)
            } else {
                echo "  [MISSING] ${sub}: config file not found at projects/${sub}.yml"
            }
        }
    } else {
        echo "Project: ${pipelineConfig.project?.name ?: 'NOT SET'}"
        echo "Repo URL: ${pipelineConfig.project?.repoUrl ?: 'NOT SET'}"
        echo "Build Scripts: ${pipelineConfig.buildScripts?.url ?: 'none (expecting build-scripts in workspace)'}"
        echo "Stages: ${pipelineConfig.stages ?: ['build']}"

        if (!pipelineConfig.project?.repoUrl) {
            echo "ERROR: project.repoUrl is required for component mode"
        }
    }

    echo "=== DRY RUN COMPLETE ==="
}

private void validateSubsystemConfig(String name, Map cfg) {
    def type = cfg.project?.type ?: 'custom'
    def builderCfg = cfg[type] ?: [:]
    if (!builderCfg.buildScript) {
        echo "    ERROR: ${name} has no ${type}.buildScript — build will fail"
    }
    if (cfg.environment?.docker?.image) {
        def image = cfg.environment.docker.image
        if (image.contains('/') && image.split('/')[0].contains('.') && !cfg.environment?.docker?.credentialId) {
            echo "    WARN: ${name} uses private registry image but no credentialId set"
        }
    }
}

// ── Workspace cleanup ───────────────────────────────────────────────

private void cleanupWorkspace(String workspace, Map cleanup) {
    stage('Cleanup') {
        echo "Cleaning intermediate build artifacts from ${workspace}"
        def keepArtifacts = cleanup.keepArtifacts != false

        (cleanup.dirs ?: []).each { entry ->
            def dirPath = entry.path
            def label = entry.label ?: dirPath
            def requiresFullClean = entry.fullCleanOnly ?: false

            if (requiresFullClean && keepArtifacts) {
                echo "Skipping ${label} (keepArtifacts=true)"
                return
            }
            if (dirPath && fileExists(dirPath)) {
                echo "Removing ${label}..."
                dir(dirPath) { deleteDir() }
            }
        }

        echo "Workspace cleanup complete"
    }
}

// ── Build script execution ──────────────────────────────────────────

/**
 * Run the build script specified in YAML config.
 * Reads buildScript from config[builderType] (e.g., yocto.buildScript, custom.buildScript).
 * For deploy stage, reads from config.deploy.buildScript.
 */
private void runBuildScript(Map config, String configKey = null) {
    def builderType = configKey ?: config.project?.type ?: 'custom'
    def builderConfig = config[builderType] ?: [:]

    def buildScript = builderConfig.buildScript
    if (!buildScript) {
        error "${builderType}.buildScript is required — set it in YAML config"
    }
    if (!fileExists(buildScript)) {
        error "${builderType}.buildScript not found: ${buildScript}"
    }

    def envVars = builderConfig.env?.collect { k, v -> "${k}=${v}" } ?: []
    def args = builderConfig.args ? Utils.shellEscape(builderConfig.args) : ''

    echo "=== Build: ${builderType} (${buildScript}) ==="
    withEnv(envVars) {
        sh "bash '${Utils.shellEscape(buildScript)}' ${args}".trim()
    }
}

// ── Stage dispatch (shared) ─────────────────────────────────────────

private void runStage(def stageEntry, Map config, PlatformFactory platforms, String subsystemName, List stageTimings = null) {
    if (stageEntry instanceof List) {
        def branches = [:]
        for (name in stageEntry) {
            def s = name
            branches[s] = { runStage(s, config, platforms, subsystemName, stageTimings) }
        }
        parallel branches
        return
    }

    String stageName = stageEntry as String
    def displayName = subsystemName ? "${subsystemName}: ${stageName}" : stageName
    stage(displayName) {
        def startTime = System.currentTimeMillis()
        def result = 'SUCCESS'
        try {
            def stagesDir = config._stagesDir ?: 'stages'
            if (fileExists("${stagesDir}/${stageName}.sh")) {
                def stageConfig = config[stageName] ?: [:]
                def envVars = stageConfig.env?.collect { k, v -> "${k}=${v}" } ?: []
                def args = stageConfig.args ?: ""
                withEnv(envVars) {
                    sh "bash '${Utils.shellEscape("${stagesDir}/${stageName}.sh")}' ${args}"
                }
                return
            }

            switch (stageName) {
                case 'checkout':
                    echo "Checkout handled externally"
                    break
                case 'build':
                    runBuildScript(config)
                    break
                case 'publish':
                    publishArtifacts(config, platforms)
                    break
                case 'notify':
                    notifyBuildStatus(config, platforms)
                    break
                case 'deploy':
                    runBuildScript(config, 'deploy')
                    break
                default:
                    error "Unknown stage '${stageName}' and no ${stagesDir}/${stageName}.sh found"
            }
        } catch (Exception e) {
            result = 'FAILURE'
            throw e
        } finally {
            if (stageTimings != null) {
                def duration = (System.currentTimeMillis() - startTime) / 1000.0
                stageTimings.add([name: stageName, duration: duration, result: result])
            }
        }
    }
}

// ── Notify and publish (shared) ─────────────────────────────────────

private void notifyBuildStatus(Map config, PlatformFactory platforms, String status = null) {
    def adapter = platforms?.getGitAdapter()
    if (!adapter) {
        echo "No git adapter configured, skipping notification"
        return
    }

    status = status ?: currentBuild.currentResult
    def state = status == 'SUCCESS' ? 'success' :
                status == 'FAILURE' ? 'failure' :
                status == 'UNSTABLE' ? 'success' : 'pending'
    def description = "${env.JOB_NAME} #${env.BUILD_NUMBER} - ${status}"

    try {
        Utils.withRetry(this, 2) { adapter.setBuildStatus(this, state, description) }
    } catch (Exception e) {
        echo "WARNING: Failed to set build status: ${e.message}"
    }

    def prId = env.CHANGE_ID
    if (prId) {
        def duration = currentBuild.durationString?.replace(' and counting', '') ?: 'unknown'
        def comment = "**Build ${status}** - #${env.BUILD_NUMBER}\nDuration: ${duration}\n[View Build](${env.BUILD_URL})"
        try {
            Utils.withRetry(this, 2) { adapter.addPrComment(this, prId, comment) }
        } catch (Exception e) {
            echo "WARNING: Failed to add PR comment: ${e.message}"
        }
    }
}

private void publishArtifacts(Map config, PlatformFactory platforms) {
    def adapter = platforms?.getArtifactAdapter()
    def publishConfig = config.publish ?: [:]
    def artifacts = publishConfig.artifacts ?: []
    def projectName = config.project?.name ?: env.JOB_BASE_NAME
    def buildType = config.project?.buildType ?: 'ci'

    def namingPattern = config.artifacts?.namingPattern ?: '${PROJECT}/${BUILD_TYPE}/${BUILD_NUMBER}'
    def releasePattern = config.artifacts?.releasePattern ?: '${PROJECT}/releases/${VERSION}'

    def remotePath
    if (buildType == 'release' && params.VERSION) {
        remotePath = releasePattern
            .replace('${PROJECT}', projectName)
            .replace('${VERSION}', params.VERSION ?: env.BUILD_NUMBER)
    } else {
        remotePath = namingPattern
            .replace('${PROJECT}', projectName)
            .replace('${BUILD_TYPE}', buildType)
            .replace('${BUILD_NUMBER}', env.BUILD_NUMBER)
    }

    def matrixValue = config._matrix?.values()?.first()
    if (matrixValue) { remotePath = "${remotePath}/${matrixValue}" }

    if (adapter && artifacts) {
        artifacts.each { artifact ->
            def pattern = artifact.pattern ?: artifact
            def repo = artifact.repo ?: config.artifacts?.defaultRepo ?: ''
            if (!repo) { error "publish: 'repo' required per artifact or set artifacts.defaultRepo" }

            def matchedFiles = findFiles(glob: pattern)
            if (!matchedFiles || matchedFiles.length == 0) {
                echo "WARNING: No files matched pattern '${pattern}'"
                return
            }
            def totalSize = matchedFiles.collect { it.length }.sum() ?: 0
            def sizeStr = totalSize > 1048576 ? "${(int)(totalSize / 1048576)} MB" : "${(int)(totalSize / 1024)} KB"
            echo "Publishing: ${pattern} (${matchedFiles.length} files, ${sizeStr}) → ${repo}/${remotePath}"
            adapter.pushRawArtifact(this, repo, remotePath, pattern)
        }
    }

    artifacts.each { artifact ->
        def pattern = artifact instanceof Map ? artifact.pattern : artifact
        if (pattern) {
            try { archiveArtifacts artifacts: pattern, allowEmptyArchive: true }
            catch (Exception e) { echo "Local archive: ${e.message}" }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

@NonCPS
private static String extractIssueId(String branchName, String pattern) {
    def name = branchName.replaceFirst(/^(refs\/heads\/|origin\/)/, '')
    def matcher = name =~ /${pattern}/
    return matcher.find() ? matcher.group(1) : null
}
