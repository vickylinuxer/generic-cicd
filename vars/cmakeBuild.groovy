import com.linuxer.cicd.builders.CmakeBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new CmakeBuilder(this).build(config)
}
