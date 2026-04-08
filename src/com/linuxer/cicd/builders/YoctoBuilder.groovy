package com.linuxer.cicd.builders

class YoctoBuilder implements Serializable {
    def script

    YoctoBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def yocto = config.yocto ?: [:]
        def buildDir = yocto.buildDir ?: '/mnt/workspace/build/yocto'
        def machine = yocto.machine ?: 'raspberrypi4-64'
        def distro = yocto.distro ?: 'poky'
        def image = yocto.image ?: 'core-image-minimal'
        def release = yocto.release ?: 'dunfell'
        def dlDir = yocto.dlDir ?: '/mnt/workspace/mirror/yocto/downloads'
        def sstateDir = yocto.sstateDir ?: yocto.sstateCacheDir ?: '/mnt/workspace/mirror/yocto/sstate-cache'
        def layers = yocto.layers ?: []
        def extraConf = yocto.extraConf ?: ''
        def threads = yocto.threads ?: 0

        def pokyDir = "${buildDir}/poky"
        def oeDir = "${buildDir}/build"

        // Setup Layers
        script.sh """#!/bin/bash
            set -e
            mkdir -p ${buildDir}
            cd ${buildDir}

            if [ ! -d poky ]; then
                git clone -b ${release} git://git.yoctoproject.org/poky.git
            else
                cd poky && git fetch && git checkout ${release} && git pull origin ${release} && cd ..
            fi
        """

        for (layer in layers) {
            def name = layer.name
            def url = layer.url
            def branch = layer.branch ?: release
            script.sh """#!/bin/bash
                set -e
                cd ${buildDir}
                if [ ! -d ${name} ]; then
                    git clone -b ${branch} ${url} ${name}
                else
                    cd ${name} && git fetch && git checkout ${branch} && git pull origin ${branch} && cd ..
                fi
            """
        }

        // Configure Build
        def cpuCount = threads
        if (cpuCount == 0) {
            cpuCount = script.sh(script: 'nproc', returnStdout: true).trim().toInteger()
        }

        def layerPaths = ["${pokyDir}/meta", "${pokyDir}/meta-poky", "${pokyDir}/meta-yocto-bsp"]
        for (layer in layers) {
            if (layer.sublayers) {
                for (sub in layer.sublayers) {
                    layerPaths.add("${buildDir}/${layer.name}/${sub}")
                }
            } else {
                layerPaths.add("${buildDir}/${layer.name}")
            }
        }

        // Initialize build env
        script.sh """#!/bin/bash
            set -e
            cd ${pokyDir}
            . oe-init-build-env ${oeDir}
        """

        // Write local.conf
        def localConf = """\
MACHINE = "${machine}"
DISTRO = "${distro}"
PACKAGE_CLASSES = "package_rpm"

DL_DIR = "${dlDir}"
SSTATE_DIR = "${sstateDir}"

BB_NUMBER_THREADS = "${cpuCount}"
PARALLEL_MAKE = "-j ${cpuCount}"

BB_DISKMON_DIRS = " \\
    STOPTASKS,\${TMPDIR},1G,100K \\
    STOPTASKS,\${DL_DIR},1G,100K \\
    STOPTASKS,\${SSTATE_DIR},1G,100K \\
    STOPTASKS,/tmp,100M,100K \\
    HALT,\${TMPDIR},100M,1K \\
    HALT,\${DL_DIR},100M,1K \\
    HALT,\${SSTATE_DIR},100M,1K \\
    HALT,/tmp,10M,1K"

${extraConf}
"""
        script.writeFile file: "${oeDir}/conf/local.conf", text: localConf

        // Write bblayers.conf
        def bblayers = """\
POKY_BBLAYERS_CONF_VERSION = "2"

BBPATH = "\${TOPDIR}"
BBFILES ?= ""

BBLAYERS ?= " \\
${layerPaths.collect { "  ${it} \\" }.join("\n")}
"
"""
        script.writeFile file: "${oeDir}/conf/bblayers.conf", text: bblayers

        // Build
        script.sh """#!/bin/bash
            set -e
            cd ${pokyDir}
            . oe-init-build-env ${oeDir}
            bitbake ${image}
        """

        // Report
        def deployDir = "${oeDir}/tmp/deploy/images/${machine}"
        script.sh """#!/bin/bash
            echo "=== Build artifacts ==="
            ls -lh ${deployDir}/ 2>/dev/null | head -20 || echo "No deploy directory found at ${deployDir}"
            echo "=== Build directory ==="
            du -sh ${oeDir}/tmp/ 2>/dev/null || true
        """
    }
}
