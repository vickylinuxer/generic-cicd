package com.linuxer.cicd

class MatrixRunner implements Serializable {
    def script
    Map config
    StageRunner stageRunner

    MatrixRunner(script, Map config, StageRunner stageRunner) {
        this.script = script
        this.config = config
        this.stageRunner = stageRunner
    }

    /**
     * Execute stages with matrix fan-out/fan-in.
     *
     * Flow:
     *   1. Run pre-matrix stages (e.g., checkout) sequentially
     *   2. Fan out: run remaining stages in parallel for each matrix value
     *   3. Converge: post-build runs once
     *
     * The matrix variable (e.g., machine) is injected into config for each branch
     * so builders see config.yocto.machine = "imx8mq-evk", etc.
     */
    void run() {
        def matrixKey = config.matrix.keySet().first()
        def matrixValues = config.matrix[matrixKey]
        def stages = config.stages ?: []

        // Determine which stages run before the matrix (shared) vs inside it
        // 'checkout' always runs before the matrix
        def preMatrixStages = []
        def matrixStages = []
        def inMatrix = false

        for (stageName in stages) {
            if (!inMatrix && isPreMatrixStage(stageName)) {
                preMatrixStages.add(stageName)
            } else {
                inMatrix = true
                matrixStages.add(stageName)
            }
        }

        // Run pre-matrix stages sequentially
        script.echo "Pre-matrix stages: ${preMatrixStages.join(' → ')}"
        for (stageName in preMatrixStages) {
            stageRunner.run(stageName)
        }

        // Fan out: parallel branches
        if (matrixStages) {
            script.echo "Matrix fan-out: ${matrixKey} = ${matrixValues}"
            script.echo "Matrix stages: ${matrixStages.join(' → ')}"

            def branches = [:]
            for (value in matrixValues) {
                def matrixValue = value // capture for closure
                branches["${matrixKey}: ${matrixValue}"] = {
                    // Create a config copy with the matrix variable injected
                    def branchConfig = ConfigLoader.deepMerge(config, [:])
                    injectMatrixValue(branchConfig, matrixKey, matrixValue)

                    // Create a stage runner for this branch
                    def branchStageRunner = new StageRunner(
                        script, branchConfig, stageRunner.platforms
                    )

                    for (stageName in matrixStages) {
                        branchStageRunner.run(stageName)
                    }
                }
            }

            script.parallel(branches)
        }
    }

    /**
     * Inject the matrix variable into the appropriate config section.
     * e.g., matrix key 'machine' → config.yocto.machine, config.cmake.target, etc.
     */
    private void injectMatrixValue(Map branchConfig, String key, String value) {
        def projectType = branchConfig.project?.type

        // Inject into the builder-specific config section
        if (projectType && branchConfig[projectType] instanceof Map) {
            branchConfig[projectType][key] = value
        }

        // Also inject as a top-level matrix value for convention scripts
        branchConfig._matrix = branchConfig._matrix ?: [:]
        branchConfig._matrix[key] = value

        // Set environment variable for convention scripts
        branchConfig.environment = branchConfig.environment ?: [:]
        branchConfig.environment["MATRIX_${key.toUpperCase()}"] = value
    }

    /**
     * Check if a stage should run before the matrix (shared).
     * Only 'checkout' runs before by default.
     */
    private boolean isPreMatrixStage(String stageName) {
        return stageName == 'checkout'
    }
}
