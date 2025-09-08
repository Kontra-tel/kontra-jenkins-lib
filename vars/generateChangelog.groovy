// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    def outputFile  = cfg.outputFile ?: 'CHANGELOG.md'
    def appendTo    = cfg.copyTo     ?: null      // optional second path to copy to
    def resetToken  = cfg.resetToken ?: '!resetLog'
    def title       = cfg.title      ?: '# Changelog'
    def version     = cfg.version    ?: (env.BUILD_VERSION ?: 'Unversioned')

    // Capture changesets once
    def changeSets = currentBuild?.changeSets ?: []
    boolean hasChanges = changeSets.size() > 0 && changeSets.any { it?.items && it.items.size() > 0 }

    if (!hasChanges) {
        echo 'No change entries detected (no commit diffs in this build)'
        return null
    }

    // Reset if requested / first time
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    // Extract commit metadata to simple serializable structures to avoid keeping GitChangeSetList (non-serializable) in scope across CPS steps
    List<Map> commitEntries = []
    if (hasChanges) {
        changeSets.each { cs ->
            cs.items.each { entry ->
                commitEntries << [
                    summary: entry.msg?.readLines()?.first()?.trim(),
                    hash: entry.commitId,
                    author: entry.author.fullName
                ]
            }
        }
    }
    // Drop original changeSets reference to prevent NotSerializableException during pipeline checkpoint
    changeSets = null

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

    commitEntries.each { e ->
        def summary    = e.summary
        def hash       = e.hash
        def shortHash  = hash.take(7)
        def commitLink = repoUrl ? "[[${shortHash}](${repoUrl}/commit/${hash})]" : "[${shortHash}]"
        changes += "- ${summary} ${commitLink}\n"
        authors << e.author
    }

    changes += '\n### Authors:\n'
    authors.each { author -> changes += "- ${author}\n" }

    // Prevent duplicate version section (idempotent if rerun in same build)
    def existing = readFile(file: outputFile)
    if (existing.contains("## ${version} (")) {
        echo "Changelog: version ${version} section already present, skipping append"
        return outputFile
    }

    echo changes
    sh "printf '%s\n' \"${changes}\" >> '${outputFile}'"

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }

    return outputFile
}
