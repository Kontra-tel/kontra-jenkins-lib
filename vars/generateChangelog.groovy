// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    def outputFile  = cfg.outputFile ?: 'CHANGELOG.md'
    def appendTo    = cfg.copyTo     ?: null      // optional second path to copy to
    def resetToken  = cfg.resetToken ?: '!resetLog'
    def title       = cfg.title      ?: '# Changelog'
    def version     = cfg.version    ?: (env.BUILD_VERSION ?: 'Unversioned')

    // Skip if no changes
    if (currentBuild?.changeSets?.size() == 0) {
        echo 'No changes detected, skipping changelog generation'
        return null
    }

    // Reset if requested / first time
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    // Resolve repo URL
    def repoUrl = env.GIT_URL
    if (!repoUrl?.trim()) {
        repoUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    }
    if (repoUrl?.endsWith('.git')) {
        repoUrl = repoUrl[0..-5]
    }

    def timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    def changes = "## ${version} (${timestamp} UTC)\n"
    def authors = [] as Set

    for (changeLog in currentBuild.changeSets) {
        for (entry in changeLog.items) {
            def summary    = entry.msg?.split('\n')[0]?.trim()
            def hash       = entry.commitId
            def shortHash  = hash.take(7)
            def commitLink = repoUrl ? "[[${shortHash}](${repoUrl}/commit/${hash})]" : "[${shortHash}]"
            changes += "- ${summary} ${commitLink}\n"
            authors << entry.author.fullName
        }
    }

    changes += '\n### Authors:\n'
    authors.each { author -> changes += "- ${author}\n" }

    echo changes
    sh "printf '%s\n' \"${changes}\" >> '${outputFile}'"

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }

    return outputFile
}
