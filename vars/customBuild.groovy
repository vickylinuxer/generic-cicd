import com.linuxer.cicd.builders.CustomBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new CustomBuilder(this).build(config)
}
