// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    return kontra.jenkins.lib.GenerateChangelogHelper.call(cfg, this)
}

