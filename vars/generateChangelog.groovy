// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    String outputFile = (cfg.outputFile ?: 'CHANGELOG.md') as String
    String appendTo   = (cfg.copyTo ?: null) as String
    String resetToken = (cfg.resetToken ?: '!resetLog') as String
    String title      = (cfg.title ?: '# Changelog') as String
    String version    = (cfg.version ?: (env.BUILD_VERSION ?: 'Unversioned')) as String

    // ---- Copy commit data into plain Maps WITHOUT closures (no @NonCPS needed)
    List<Map> commitEntries = []
    def csList = currentBuild?.changeSets
    if (csList) {
        for (def cs : csList) {
            def items = cs?.items
            if (items) {
                for (def entry : items) {
                    String msgFirstLine = ((entry?.msg ?: '') as String).readLines().with { it ? it[0].trim() : '' }
                    commitEntries.add([
                        summary: msgFirstLine,
                        hash   : (entry?.commitId ?: '') as String,
                        author : (entry?.author?.fullName ?: '') as String
                    ])
                }
            }
        }
    }

    if (commitEntries.isEmpty()) {
        echo 'No change entries detected (no commit diffs in this build)'
        return null
    }

    // ---- Now it is safe to call pipeline steps
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    // Resolve repo URL once
    String repoUrl = env.GIT_URL?.trim()
    if (!repoUrl) {
        repoUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    }
    if (repoUrl?.endsWith('.git')) repoUrl = repoUrl[0..-5]

    String timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    StringBuilder changes = new StringBuilder()
    changes.append("## ").append(version).append(" (").append(timestamp).append(" UTC)\n")

    // Collect authors with stable order
    LinkedHashSet<String> authors = new LinkedHashSet<>()
    for (Map e : commitEntries) {
        String hash = e.hash ?: ''
        String shortHash = hash.length() >= 7 ? hash.substring(0,7) : hash
        String link = repoUrl ? "[[${shortHash}](${repoUrl}/commit/${hash})]" : "[${shortHash}]"
        changes.append("- ").append(e.summary ?: '').append(' ').append(link).append('\n')
        if (e.author) authors.add(e.author)
    }

    changes.append('\n### Authors:\n')
    for (String a : authors) {
        changes.append("- ").append(a).append('\n')
    }

    // Idempotency: skip if same version block already exists
    String existing = readFile(file: outputFile)
    if (existing.contains("## ${version} (")) {
        echo "Changelog: version ${version} section already present, skipping append"
        return outputFile
    }

    echo changes.toString()
    sh "printf '%s\\n' \"${changes}\" >> '${outputFile}'"

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }

    return outputFile
}
