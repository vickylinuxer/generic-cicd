package com.linuxer.cicd.builders

class AospBuilder implements Serializable {
    def script

    AospBuilder(script) {
        this.script = script
    }

    void build(Map config) {
        def aosp = config.aosp ?: [:]
        def mirrorDir = aosp.mirrorDir ?: '/mnt/workspace/mirror/aosp'
        def buildDir = aosp.buildDir ?: '/mnt/workspace/build/aosp'
        def branch = aosp.branch ?: 'android-14.0.0_r1'
        def target = aosp.target ?: 'aosp_cf_x86_64_phone-userdebug'
        def jobs = aosp.jobs ?: 8
        def ccacheDir = aosp.ccacheDir ?: '/mnt/workspace/cache/ccache'
        def ccacheSize = aosp.ccacheSize ?: '50G'

        // Init Repo
        script.withEnv([
            "BUILD_DIR=${buildDir}",
            "MIRROR_DIR=${mirrorDir}",
            "BRANCH=${branch}"
        ]) {
            script.sh '''
                mkdir -p "$BUILD_DIR"
                cd "$BUILD_DIR"
                if [ ! -d .repo ]; then
                    repo init -u "$MIRROR_DIR" -b "$BRANCH" --reference="$MIRROR_DIR"
                else
                    repo init -u "$MIRROR_DIR" -b "$BRANCH" --reference="$MIRROR_DIR"
                fi
            '''
        }

        // Sync
        script.withEnv(["BUILD_DIR=${buildDir}", "JOBS=${jobs}"]) {
            script.sh '''
                cd "$BUILD_DIR"
                repo sync -j"$JOBS" --optimized-fetch --force-sync
            '''
        }

        // Build
        script.withEnv([
            "BUILD_DIR=${buildDir}",
            "CCACHE_DIR=${ccacheDir}",
            "TARGET=${target}",
            "JOBS=${jobs}",
            "CCACHE_SIZE=${ccacheSize}"
        ]) {
            script.sh '''
                cd "$BUILD_DIR"
                export USE_CCACHE=1
                export CCACHE_DIR="$CCACHE_DIR"
                export CCACHE_EXEC=$(which ccache)
                mkdir -p "$CCACHE_DIR"
                ccache -M "$CCACHE_SIZE" 2>/dev/null || true

                source build/envsetup.sh
                lunch "$TARGET"
                m -j"$JOBS"
            '''
        }

        // Report
        script.withEnv(["BUILD_DIR=${buildDir}"]) {
            script.sh '''
                cd "$BUILD_DIR"
                echo "=== Build Output ==="
                if [ -d out/target/product ]; then
                    find out/target/product -name '*.img' -type f | head -20
                    echo "Total output size:"
                    du -sh out/target/product/*/
                else
                    echo "No build output found"
                fi
            '''
        }
    }
}
