import com.linuxer.cicd.builders.AospBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new AospBuilder(this).build(config)
}
