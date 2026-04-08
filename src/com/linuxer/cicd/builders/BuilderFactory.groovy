package com.linuxer.cicd.builders

class BuilderFactory implements Serializable {

    /**
     * Create a builder based on project.type in config.
     * Supported types: yocto, aosp, qnx, autosar, cmake, custom
     */
    static def create(def script, Map config) {
        def projectType = config.project?.type ?: 'custom'

        switch (projectType) {
            case 'yocto':
                return new YoctoBuilder(script)
            case 'aosp':
                return new AospBuilder(script)
            case 'qnx':
                return new QnxBuilder(script)
            case 'autosar':
                return new AutosarBuilder(script)
            case 'cmake':
                return new CmakeBuilder(script)
            case 'custom':
                return new CustomBuilder(script)
            default:
                script.echo "Unknown project type '${projectType}', falling back to custom builder"
                return new CustomBuilder(script)
        }
    }
}
