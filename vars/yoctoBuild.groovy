import com.linuxer.cicd.builders.YoctoBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new YoctoBuilder(this).build(config)
}
