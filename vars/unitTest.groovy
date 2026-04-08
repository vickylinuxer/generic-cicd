def call(Map params = [:]) {
    def config = params.config ?: [:]
    def testConfig = config.test ?: config.unitTest ?: params
    def framework = testConfig.framework ?: 'auto'
    def junitResults = testConfig.junitResults ?: '**/test-results/*.xml'
    def testArgs = testConfig.args ?: ''

    echo "Running tests (framework: ${framework})"

    switch (framework) {
        case 'unity':
            runUnityTests(testArgs)
            break
        case 'gtest':
            runGTestTests(testArgs)
            break
        case 'pytest':
            runPytestTests(testArgs)
            break
        case 'ctest':
            runCTestTests(testArgs)
            break
        case 'auto':
            autoDetectAndRun(testArgs)
            break
        case 'script':
            def testScript = testConfig.script ?: 'test.sh'
            sh "chmod +x ${testScript} && ./${testScript} ${testArgs}"
            break
        default:
            echo "Unknown test framework: ${framework}"
    }

    // Collect JUnit results
    try {
        junit testResults: junitResults, allowEmptyResults: true
    } catch (Exception e) {
        echo "JUnit result collection: ${e.message}"
    }
}

private void runUnityTests(String args) {
    sh """#!/bin/bash
        set -e
        if [ -f Makefile ] && grep -q 'test' Makefile; then
            make test ${args}
        elif [ -f build/Makefile ] && grep -q 'test' build/Makefile; then
            make -C build test ${args}
        else
            echo "No Unity test target found in Makefile"
            exit 1
        fi
    """
}

private void runGTestTests(String args) {
    sh """#!/bin/bash
        set -e
        mkdir -p test-results
        # Find and run gtest executables
        find build -name '*_test' -o -name '*_tests' -o -name 'test_*' | while read test_bin; do
            if [ -x "\$test_bin" ]; then
                echo "Running: \$test_bin"
                "\$test_bin" --gtest_output=xml:test-results/ ${args} || true
            fi
        done
    """
}

private void runPytestTests(String args) {
    sh """#!/bin/bash
        set -e
        python3 -m pytest --junitxml=test-results/pytest-results.xml ${args} || true
    """
}

private void runCTestTests(String args) {
    sh """#!/bin/bash
        set -e
        cd build
        ctest --output-on-failure --output-junit ../test-results/ctest-results.xml ${args} || true
    """
}

private void autoDetectAndRun(String args) {
    // Try to auto-detect test framework
    if (fileExists('pytest.ini') || fileExists('setup.py') || fileExists('pyproject.toml')) {
        echo "Auto-detected: pytest"
        runPytestTests(args)
    } else if (fileExists('build/CTestTestfile.cmake')) {
        echo "Auto-detected: ctest"
        runCTestTests(args)
    } else if (fileExists('Makefile')) {
        echo "Auto-detected: make test"
        sh "make test ${args} || true"
    } else {
        echo "No test framework auto-detected, skipping"
    }
}
