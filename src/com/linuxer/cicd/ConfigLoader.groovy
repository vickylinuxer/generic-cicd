package com.linuxer.cicd

class ConfigLoader implements Serializable {
    def script

    ConfigLoader(script) {
        this.script = script
    }

    /**
     * Load configuration with 4-layer merge:
     *   1. Library defaults (resources/defaults/)
     *   2. Project platforms (platforms.yml in project root)
     *   3. Project config (cicd.yml in project root)
     *   4. Build-type override (cicd-{buildType}.yml, optional)
     *   + env CICD_* overrides + runtime overrides map
     */
    Map load(Map overrides = [:]) {
        // Layer 1: Library defaults
        def config = loadDefaults()

        // Layer 2: Project platform overrides
        if (script.fileExists('platforms.yml')) {
            def projectPlatforms = script.readYaml(file: 'platforms.yml')
            config = deepMerge(config, projectPlatforms ?: [:])
        }

        // Layer 3: Project config (cicd.yml)
        if (script.fileExists('cicd.yml')) {
            def projectConfig = script.readYaml(file: 'cicd.yml')
            config = deepMerge(config, projectConfig ?: [:])
        }

        // Resolve buildType (may contain env var reference)
        def buildType = resolveBuildType(config)
        config.project = config.project ?: [:]
        config.project.buildType = buildType

        // Layer 4: Build-type override (cicd-{buildType}.yml)
        def btOverrideFile = "cicd-${buildType}.yml"
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

        return config
    }

    private Map loadDefaults() {
        def platformsYml = script.libraryResource('com/linuxer/cicd/defaults/platforms.yml')
        def buildYml = script.libraryResource('com/linuxer/cicd/defaults/build.yml')

        def platforms = script.readYaml(text: platformsYml) ?: [:]
        def build = script.readYaml(text: buildYml) ?: [:]

        return deepMerge(platforms, build)
    }

    private String resolveBuildType(Map config) {
        def raw = config.project?.buildType ?: 'ci'
        // Support ${CICD_BUILD_TYPE:-default} syntax
        def matcher = raw =~ /\$\{(\w+):-(\w+)\}/
        if (matcher.find()) {
            def envVar = matcher.group(1)
            def defaultVal = matcher.group(2)
            return script.env[envVar] ?: defaultVal
        }
        // Support ${CICD_BUILD_TYPE} syntax
        matcher = raw =~ /\$\{(\w+)\}/
        if (matcher.find()) {
            return script.env[matcher.group(1)] ?: 'ci'
        }
        return raw
    }

    private Map applyEnvOverrides(Map config) {
        // CICD_AGENT → environment.agent
        def agent = script.env.CICD_AGENT
        if (agent) {
            config.environment = config.environment ?: [:]
            config.environment.agent = agent
        }

        // CICD_TIMEOUT → environment.timeout
        def timeout = script.env.CICD_TIMEOUT
        if (timeout) {
            config.environment = config.environment ?: [:]
            config.environment.timeout = timeout.toInteger()
        }

        // CICD_DOCKER_IMAGE → environment.docker.image
        def dockerImage = script.env.CICD_DOCKER_IMAGE
        if (dockerImage) {
            config.environment = config.environment ?: [:]
            config.environment.docker = config.environment.docker ?: [:]
            config.environment.docker.image = dockerImage
        }

        return config
    }

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
