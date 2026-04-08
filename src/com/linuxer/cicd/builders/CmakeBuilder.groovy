package com.linuxer.cicd.builders

class CmakeBuilder implements Serializable {
    def script

    CmakeBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def cmake = config.cmake ?: [:]
        def buildDir = cmake.buildDir ?: 'build'
        def buildType = cmake.buildType ?: 'Release'
        def toolchainFile = cmake.toolchainFile ?: ''
        def target = cmake.target ?: ''
        def cmakeArgs = cmake.args ?: ''
        def installPrefix = cmake.installPrefix ?: ''
        def generator = cmake.generator ?: ''
        def dockerImage = config.environment?.docker?.image ?: ''

        def buildClosure = {
            // Configure
            def configCmd = "cmake -B ${buildDir} -DCMAKE_BUILD_TYPE=${buildType}"

            if (toolchainFile) {
                configCmd += " -DCMAKE_TOOLCHAIN_FILE=${toolchainFile}"
            }
            if (installPrefix) {
                configCmd += " -DCMAKE_INSTALL_PREFIX=${installPrefix}"
            }
            if (generator) {
                configCmd += " -G '${generator}'"
            }
            if (cmakeArgs) {
                configCmd += " ${cmakeArgs}"
            }

            script.sh """#!/bin/bash
                set -e
                echo "CMake Configure"
                echo "Build Type: ${buildType}"
                ${toolchainFile ? "echo \"Toolchain: ${toolchainFile}\"" : ''}
                ${target ? "echo \"Target: ${target}\"" : ''}

                ${configCmd}
            """

            // Build
            def buildCmd = "cmake --build ${buildDir} --parallel \$(nproc)"
            if (target) {
                buildCmd += " --target ${target}"
            }

            script.sh """#!/bin/bash
                set -e
                ${buildCmd}
            """

            // Install (if prefix set)
            if (installPrefix) {
                script.sh """#!/bin/bash
                    set -e
                    cmake --install ${buildDir}
                """
            }

            // Report
            script.sh """#!/bin/bash
                echo "=== Build Output ==="
                find ${buildDir} -type f -executable -not -path '*/CMakeFiles/*' 2>/dev/null | head -20
                echo "=== Build Size ==="
                du -sh ${buildDir}/ 2>/dev/null || true
            """
        }

        // Run inside Docker if configured
        if (dockerImage) {
            script.docker.image(dockerImage).inside(config.environment?.docker?.args ?: '') {
                buildClosure()
            }
        } else {
            buildClosure()
        }
    }
}
