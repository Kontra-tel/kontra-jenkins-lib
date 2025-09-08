// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    def outputFile  = cfg.outputFile ?: 'CHANGELOG.md'
    def appendTo    = cfg.copyTo     ?: null
    def resetToken  = cfg.resetToken ?: '!resetLog'
    def title       = cfg.title      ?: '# Changelog'
    def version     = cfg.version    ?: (env.BUILD_VERSION ?: 'Unversioned')

    // Extract commit entries FIRST (NonCPS), so no GitChangeSet objects remain in scope
    List<Map> commitEntries = extractCommitEntries(currentBuild?.changeSets)
    if (!commitEntries || commitEntries.isEmpty()) {
        echo 'No change entries detected (no commit diffs in this build)'
        return null
    }

    // Safe to use steps now
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    def repoUrl = env.GIT_URL?.trim()
    if (!repoUrl) {
        repoUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    }
    if (repoUrl?.endsWith('.git')) repoUrl = repoUrl[0..-5]

    def timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    def changes = "## ${version} (${timestamp} UTC)\n"
    def authors = [] as Set

    commitEntries.each { e ->
        def shortHash  = (e.hash ?: '').take(7)
        def link = repoUrl ? "[[${shortHash}](${repoUrl}/commit/${e.hash})]" : "[${shortHash}]"
        changes += "- ${e.summary ?: ''} ${link}\n"
        if (e.author) authors << e.author
    }

    changes += '\n### Authors:\n'
    authors.each { a -> changes += "- ${a}\n" }

    def existing = fileExists(outputFile) ? readFile(file: outputFile) : ''
    if (existing.contains("## ${version} (")) {
        echo "Changelog: version ${version} section already present, skipping append"
        return outputFile
    }

    echo changes
    sh "printf '%s\\n' \"${changes}\" >> '${outputFile}'"

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }
    return outputFile
}

@org.jenkinsci.plugins.workflow.cps.NonCPS
private List<Map> extractCommitEntries(def changeSets) {
    def out = []
    if (changeSets) {
        for (def cs : changeSets) {
            def items = cs?.items
            if (!items) continue
            for (def entry : items) {
                def firstLine = (entry?.msg ?: '').readLines()?.first()?.trim()
                out << [
                    summary: firstLine ?: '',
                    hash   : entry?.commitId ?: '',
                    author : entry?.author?.fullName ?: ''
                ]
            }
        }
    }
    return out
}
