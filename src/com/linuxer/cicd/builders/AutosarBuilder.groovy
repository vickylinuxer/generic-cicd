package com.linuxer.cicd.builders

class AutosarBuilder implements Serializable {
    def script

    AutosarBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def autosar = config.autosar ?: [:]
        def toolchain = autosar.toolchain ?: 'gcc'
        def target = autosar.target ?: 'VRTE'
        def buildDir = autosar.buildDir ?: 'build'
        def configFile = autosar.configFile ?: 'autosar_config.arxml'
        def buildCommand = autosar.buildCommand ?: ''
        def dockerImage = config.environment?.docker?.image ?: ''

        def buildClosure = {
            // Configure
            script.sh """#!/bin/bash
                set -e
                echo "AUTOSAR Build"
                echo "Toolchain: ${toolchain}"
                echo "Target: ${target}"
                echo "Config: ${configFile}"

                mkdir -p ${buildDir}
            """

            if (buildCommand) {
                // Custom build command
                script.sh """#!/bin/bash
                    set -e
                    ${buildCommand}
                """
            } else {
                // Default CMake-based AUTOSAR build
                script.sh """#!/bin/bash
                    set -e
                    cd ${buildDir}
                    cmake .. \\
                        -DCMAKE_TOOLCHAIN_FILE=../cmake/${toolchain}-toolchain.cmake \\
                        -DAUTOSAR_TARGET=${target} \\
                        -DAUTOSAR_CONFIG=../${configFile}
                    cmake --build . --parallel \$(nproc)
                """
            }

            // Report
            script.sh """#!/bin/bash
                echo "=== Build Output ==="
                find ${buildDir} -name '*.elf' -o -name '*.hex' -o -name '*.bin' 2>/dev/null | head -20
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
