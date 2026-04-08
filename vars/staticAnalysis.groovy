def call(Map params = [:]) {
    def config = params.config ?: [:]
    def tools = params.tools ?: config.staticAnalysis?.tools ?: ['cppcheck']
    def failOnWarning = params.failOnWarning != null ? params.failOnWarning :
                        (config.staticAnalysis?.failOnWarning != null ? config.staticAnalysis.failOnWarning : false)
    def srcDirs = params.srcDirs ?: config.staticAnalysis?.srcDirs ?: ['src', '.']
    def excludeDirs = params.excludeDirs ?: config.staticAnalysis?.excludeDirs ?: ['build', 'third_party', 'vendor']

    def srcPath = srcDirs.join(' ')
    def excludeArgs = excludeDirs.collect { "-i ${it}" }.join(' ')
    def hasWarnings = false

    for (tool in tools) {
        echo "Running static analysis: ${tool}"

        switch (tool) {
            case 'cppcheck':
                def cppcheckArgs = "--enable=all --xml --xml-version=2 ${excludeArgs}"
                if (failOnWarning) {
                    cppcheckArgs += " --error-exitcode=1"
                }
                sh """#!/bin/bash
                    set -e
                    cppcheck ${cppcheckArgs} ${srcPath} 2> cppcheck-results.xml || true
                    echo "cppcheck analysis complete"
                """
                // Publish cppcheck results if plugin available
                try {
                    recordIssues(
                        tools: [cppCheck(pattern: 'cppcheck-results.xml')],
                        qualityGates: failOnWarning ? [[threshold: 1, type: 'TOTAL', unstable: false]] : []
                    )
                } catch (Exception e) {
                    echo "Warnings Next Generation plugin not available, archiving raw results"
                    archiveArtifacts artifacts: 'cppcheck-results.xml', allowEmptyArchive: true
                }
                break

            case 'clang-tidy':
                def checks = params.checks ?: config.staticAnalysis?.checks ?: ''
                def checksArg = checks ? "-checks='${checks}'" : ''
                sh """#!/bin/bash
                    set -e
                    if [ -f compile_commands.json ] || [ -f build/compile_commands.json ]; then
                        COMP_DB=""
                        [ -f build/compile_commands.json ] && COMP_DB="-p build"
                        [ -f compile_commands.json ] && COMP_DB="-p ."

                        find ${srcPath} -name '*.cpp' -o -name '*.c' -o -name '*.h' | \
                            head -500 | \
                            xargs clang-tidy \$COMP_DB ${checksArg} 2>&1 | \
                            tee clang-tidy-results.txt || true
                    else
                        echo "WARNING: No compile_commands.json found, skipping clang-tidy"
                    fi
                """
                archiveArtifacts artifacts: 'clang-tidy-results.txt', allowEmptyArchive: true
                break

            case 'coverity':
                def covStream = params.covStream ?: config.staticAnalysis?.covStream ?: env.JOB_NAME
                sh """#!/bin/bash
                    set -e
                    cov-build --dir cov-int make
                    cov-analyze --dir cov-int
                    cov-format-errors --dir cov-int --json-output-v8 coverity-results.json
                """
                archiveArtifacts artifacts: 'coverity-results.json', allowEmptyArchive: true
                break

            default:
                echo "Unknown static analysis tool: ${tool}, skipping"
        }
    }
}
