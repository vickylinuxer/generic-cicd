package com.tcs.cicd

import com.cloudbees.groovy.cps.NonCPS

class ConfigLoader implements Serializable {
    def script

    static final List KNOWN_BUILDER_TYPES = ['yocto', 'aosp', 'custom']

    static final List KNOWN_TOP_LEVEL_KEYS = [
        'project', 'environment', 'stages', 'matrix',
        'git', 'artifacts', 'credentials',
        'yocto', 'aosp', 'custom',
        'cache', 'notify', 'buildTypes',
        'publish', 'buildScripts',
        'deploy', 'fetchSources', 'build', 'checkout',
        'cleanup', 'branchStrategy',
        'mode', 'workspace', 'manifest', 'subsystems', 'mirrors',
        'failFast', 'cleanWorkspace', 'dryRun', 'metrics',
        '_buildTypeConfig', '_stagesDir', '_issueId', '_matrix'
    ]

    static final Map STAGE_PLATFORM_DEPS = [
        'publish' : 'artifacts',
        'notify'  : 'git'
    ]

    ConfigLoader(script) {
        this.script = script
    }

    // ── Config loading ──────────────────────────────────────────────────

    /**
     * Load configuration with 4-layer merge:
     *   1. Library defaults (resources/defaults/)
     *   2. Project platforms (platforms.yml in project root)
     *   3. Project config (cicd.yml in project root)
     *   4. Build-type override (cicd-{buildType}.yml, optional)
     *   + env CICD_* overrides + runtime overrides map
     */
    Map load(Map overrides = [:], String configFile = null, String platformsFile = null) {
        // Layer 1: Library defaults
        def config = loadDefaults()

        // Layer 2: Project platform overrides
        def pFile = platformsFile ?: 'platforms.yml'
        if (script.fileExists(pFile)) {
            def projectPlatforms = script.readYaml(file: pFile)
            config = deepMerge(config, projectPlatforms ?: [:])
        }

        // Layer 3: Project config (cicd.yml or external configFile)
        def cFile = configFile ?: 'cicd.yml'
        if (script.fileExists(cFile)) {
            def projectConfig = script.readYaml(file: cFile)
            config = deepMerge(config, projectConfig ?: [:])
        }

        // Resolve buildType (may contain env var reference)
        def buildType = resolveBuildType(config)
        config.project = config.project ?: [:]
        config.project.buildType = buildType

        // Layer 4: Build-type override (cicd-{buildType}.yml)
        def configDir = configFile ? new File(configFile).parent ?: '.' : '.'
        def btOverrideFile = "${configDir}/cicd-${buildType}.yml"
        if (script.fileExists(btOverrideFile)) {
            def btConfig = script.readYaml(file: btOverrideFile)
            config = deepMerge(config, btConfig ?: [:])
        }

        // Apply buildType defaults from build.yml
        def btDefaults = config.buildTypes?.get(buildType) ?: [:]
        config._buildTypeConfig = btDefaults

        // Apply environment CICD_* overrides
        config = applyEnvOverrides(config)

        // Apply runtime overrides
        if (overrides) {
            config = deepMerge(config, overrides)
        }

        // Ensure required fields
        config.environment = config.environment ?: [:]
        config.stages = config.stages ?: ['checkout', 'build']

        // Validate merged config
        validate(config)

        return config
    }

    private Map loadDefaults() {
        def platformsYml = script.libraryResource('config/platforms.yml')
        def buildYml = script.libraryResource('config/build.yml')

        def platforms = script.readYaml(text: platformsYml) ?: [:]
        def build = script.readYaml(text: buildYml) ?: [:]

        return deepMerge(platforms, build)
    }

    private String resolveBuildType(Map config) {
        def raw = config.project?.buildType ?: 'ci'
        def resolved = parseEnvVarSyntax(raw)
        if (resolved != null) {
            return script.env[resolved.varName] ?: resolved.defaultVal
        }
        return raw
    }

    @NonCPS
    private static Map parseEnvVarSyntax(String raw) {
        def matcher = raw =~ /\$\{(\w+):-(\w+)\}/
        if (matcher.find()) {
            return [varName: matcher.group(1), defaultVal: matcher.group(2)]
        }
        matcher = raw =~ /\$\{(\w+)\}/
        if (matcher.find()) {
            return [varName: matcher.group(1), defaultVal: 'ci']
        }
        return null
    }

    private Map applyEnvOverrides(Map config) {
        def agent = script.env.CICD_AGENT
        if (agent) {
            config.environment = config.environment ?: [:]
            config.environment.agent = agent
        }

        def timeout = script.env.CICD_TIMEOUT
        if (timeout) {
            config.environment = config.environment ?: [:]
            config.environment.timeout = timeout.toInteger()
        }

        def dockerImage = script.env.CICD_DOCKER_IMAGE
        if (dockerImage) {
            config.environment = config.environment ?: [:]
            config.environment.docker = config.environment.docker ?: [:]
            config.environment.docker.image = dockerImage
        }

        return config
    }

    // ── Validation ──────────────────────────────────────────────────────

    /**
     * Validate merged config. Logs warnings for non-fatal issues,
     * throws error for fatal misconfigurations.
     */
    private void validate(Map config) {
        validateProjectType(config)
        validateStages(config)
        validateBuilderFields(config)
        validatePlatformDeps(config)
        validateCredentials(config)
        validateCacheEntries(config)
        validateEnvironment(config)
        validateDockerConfig(config)
        warnUnknownKeys(config)
    }

    private void validateProjectType(Map config) {
        def projectType = config.project?.type
        if (projectType && !(projectType in KNOWN_BUILDER_TYPES)) {
            script.error "Invalid project.type '${projectType}'. Must be one of: ${KNOWN_BUILDER_TYPES.join(', ')}"
        }
    }

    private void validateStages(Map config) {
        def stages = config.stages
        if (stages != null && !(stages instanceof List)) {
            script.error "'stages' must be a list, got: ${stages.getClass().simpleName}"
        }
    }

    private void validateBuilderFields(Map config) {
        def projectType = config.project?.type ?: 'custom'
        if (!(projectType in KNOWN_BUILDER_TYPES)) return

        def builderConfig = config[projectType] ?: [:]
        if (!builderConfig.buildScript) {
            script.error "${projectType}.buildScript is required — all builds must use an external script from the build-scripts repo"
        }
    }

    private void validatePlatformDeps(Map config) {
        def stages = config.stages ?: []
        STAGE_PLATFORM_DEPS.each { stageName, platformKey ->
            if (stageName in stages) {
                def platformConfig = config[platformKey]
                if (!platformConfig || !platformConfig.url) {
                    script.echo "WARNING: Stage '${stageName}' requires ${platformKey}.url to be configured"
                }
                if (!platformConfig || !platformConfig.credentialId) {
                    script.echo "WARNING: Stage '${stageName}' requires ${platformKey}.credentialId to be configured"
                }
            }
        }
    }

    private void validateCredentials(Map config) {
        def stages = config.stages ?: []
        def creds = config.credentials ?: [:]
        def gitCred = creds.git ?: config.git?.credentialId ?: ''

        def needsGit = stages.any { it in ['fetchSources', 'checkout', 'notify'] }
        if (needsGit && !gitCred) {
            script.echo "WARNING: No git credential configured (set credentials.git or git.credentialId)"
        }
    }

    private void validateCacheEntries(Map config) {
        if (!(config.cache instanceof List)) return
        config.cache.eachWithIndex { entry, idx ->
            if (!entry.src) {
                script.error "cache[${idx}].src is required"
            }
        }
    }

    private void validateEnvironment(Map config) {
        def timeout = config.environment?.timeout
        if (timeout != null) {
            try {
                def val = timeout as int
                if (val <= 0) {
                    script.error "environment.timeout must be a positive number, got: ${timeout}"
                }
            } catch (Exception e) {
                script.error "environment.timeout must be a positive number, got: ${timeout}"
            }
        }
    }

    private void validateDockerConfig(Map config) {
        def dockerArgs = config.environment?.docker?.args
        def dockerImage = config.environment?.docker?.image
        if (dockerArgs && !dockerImage) {
            script.error "environment.docker.args is set but environment.docker.image is missing"
        }
    }

    /**
     * Validate pipeliner-specific config (called from pipeliner.groovy).
     */
    static void validatePipelinerConfig(def script, Map config) {
        def mode = config.mode ?: 'component'

        if (!(mode in ['integration', 'component'])) {
            script.error "Invalid mode '${mode}'. Must be 'integration' or 'component'"
        }

        if (mode == 'integration') {
            if (!config.manifest?.url) {
                script.error "manifest.url is required for integration mode"
            }
            if (!config.subsystems || config.subsystems.isEmpty()) {
                script.error "subsystems list is required and cannot be empty for integration mode"
            }
        }

        if (mode == 'component') {
            if (!config.project?.repoUrl) {
                script.error "project.repoUrl is required for component mode"
            }
        }

        def dockerImage = config.environment?.docker?.image ?: ''
        def dockerCred = config.environment?.docker?.credentialId ?: ''
        if (dockerCred && dockerImage && !dockerImage.contains('.')) {
            script.echo "WARNING: docker.credentialId is set but image '${dockerImage}' does not appear to reference a private registry"
        }
    }

    private void warnUnknownKeys(Map config) {
        config.keySet().each { key ->
            if (!(key in KNOWN_TOP_LEVEL_KEYS)) {
                script.echo "WARNING: Unknown config key '${key}' — possible typo?"
            }
        }
    }

    // ── Deep merge ──────────────────────────────────────────────────────

    /**
     * Deep merge two maps. Values in 'override' take precedence.
     * Lists are replaced, not appended.
     */
    static Map deepMerge(Map base, Map override) {
        def result = [:] + base
        override.each { key, value ->
            if (value instanceof Map && result[key] instanceof Map) {
                result[key] = deepMerge(result[key] as Map, value as Map)
            } else {
                result[key] = value
            }
        }
        return result
    }
}
