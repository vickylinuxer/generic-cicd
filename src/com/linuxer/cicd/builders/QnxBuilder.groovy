package com.linuxer.cicd.builders

class QnxBuilder implements Serializable {
    def script

    QnxBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def qnx = config.qnx ?: [:]
        def sdpPath = qnx.sdpPath ?: '/opt/qnx710'
        def target = qnx.target ?: 'aarch64le'
        def buildDir = qnx.buildDir ?: 'build'
        def makeTarget = qnx.makeTarget ?: 'all'
        def makeArgs = qnx.makeArgs ?: ''
        def dockerImage = config.environment?.docker?.image ?: ''

        def buildClosure = {
            // Source QNX SDP environment
            script.sh """#!/bin/bash
                set -e
                source ${sdpPath}/qnxsdp-env.sh

                echo "QNX SDP: \$QNX_HOST"
                echo "Target: ${target}"
                echo "\$QNX_TARGET"

                mkdir -p ${buildDir}
            """

            // Build
            script.sh """#!/bin/bash
                set -e
                source ${sdpPath}/qnxsdp-env.sh
                export CPUVARDIR=${target}

                make ${makeTarget} ${makeArgs}
            """

            // Report
            script.sh """#!/bin/bash
                echo "=== Build Output ==="
                find ${buildDir} -type f -executable 2>/dev/null | head -20 || echo "No executables found"
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
