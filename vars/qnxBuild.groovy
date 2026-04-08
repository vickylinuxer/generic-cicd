import com.linuxer.cicd.builders.QnxBuilder

def call(Map params = [:]) {
    def config = params.config ?: params
    new QnxBuilder(this).build(config)
}
