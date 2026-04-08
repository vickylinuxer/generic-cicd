import com.linuxer.cicd.builders.AutosarBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new AutosarBuilder(this).build(config)
}
