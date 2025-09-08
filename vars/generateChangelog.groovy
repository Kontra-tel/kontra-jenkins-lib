// vars/generateChangelog.groovy
import org.jenkinsci.plugins.workflow.cps.NonCPS

def call(Map cfg = [:]) {
    def outputFile  = cfg.outputFile ?: 'CHANGELOG.md'
    def appendTo    = cfg.copyTo     ?: null
    def resetToken  = cfg.resetToken ?: '!resetLog'
    def title       = cfg.title      ?: '# Changelog'
    def version     = cfg.version    ?: (env.BUILD_VERSION ?: 'Unversioned')

    // ---- Extract commit entries BEFORE any pipeline steps run
    List<Map> commitEntries = extractCommitEntries(currentBuild?.changeSets)
    if (!commitEntries || commitEntries.isEmpty()) {
        echo 'No change entries detected (no commit diffs in this build)'
        return null
    }

    // ---- Now it's safe to call steps (no GitChangeSetList in scope)
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

    commitEntries.each { e ->
        def summary    = e.summary
        def hash       = e.hash
        def shortHash  = hash.take(7)
        def commitLink = repoUrl ? "[[${shortHash}](${repoUrl}/commit/${hash})]" : "[${shortHash}]"
        changes += "- ${summary} ${commitLink}\n"
        if (e.author) authors << e.author
    }

    changes += '\n### Authors:\n'
    authors.each { a -> changes += "- ${a}\n" }

    // Idempotency: skip if this version block already exists
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

@NonCPS
private List<Map> extractCommitEntries(def changeSets) {
    def out = []
    if (changeSets) {
        for (def cs : changeSets) {
            if (!cs?.items) continue
            for (def entry : cs.items) {
                def firstLine = (entry?.msg ?: '').readLines()?.first()?.trim()
                out << [
                    summary: firstLine,
                    hash   : entry?.commitId ?: '',
                    author : entry?.author?.fullName ?: ''
                ]
            }
        }
    }
    return out
}
