import kontra.jenkins.lib.GenerateChangelogHelper

def call(Map cfg = [:]) {
    def helper = new GenerateChangelogHelper(this)
    return helper.call(cfg)
}