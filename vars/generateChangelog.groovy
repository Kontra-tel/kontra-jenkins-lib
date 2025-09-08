// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    String outputFile  = (cfg.outputFile ?: 'CHANGELOG.md') as String
    String appendTo    = (cfg.copyTo ?: null) as String
    String resetToken  = (cfg.resetToken ?: '!resetLog') as String
    String title       = (cfg.title ?: '# Changelog') as String
    String version     = (cfg.version ?: (env.BUILD_VERSION ?: 'Unversioned')) as String
    String tagPattern  = (cfg.tagPattern ?: 'v[0-9]*') as String
    Integer maxCommits = (cfg.maxCommits ?: 0) as Integer // 0 = no limit

    // 1) Decide the log range:
    // Prefer Jenkins-provided previous commits; else fall back to last tag; else last 50 commits.
    String base = (cfg.since ?: '') as String
    if (!base?.trim()) {
        base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT ?: ''
    }
    if (!base?.trim()) {
        base = sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true",
                  returnStdout: true).trim()
    }
    String range = base?.trim() ? "${base}..HEAD" : "HEAD~50..HEAD"

    // 2) Pull commits in a machine-friendly format (no merges).
    // Use unit separators to be robust against pipes/quotes in subjects.
    String fmt = "%H%x1f%an%x1f%s%x1e"
    String limit = maxCommits > 0 ? "--max-count=${maxCommits}" : ""
    String raw = sh(
        script: "git log --no-merges ${limit} --format='${fmt}' ${range}",
        returnStdout: true
    ).trim()

    if (!raw) {
        echo "No change entries detected for range: ${range}"
        return null
    }

    // 3) Parse to simple maps (CPS-safe).
    List<Map> commits = []
    for (String rec : raw.split('\\u001e')) {           // record sep
        if (!rec) continue
        String[] parts = rec.split('\\u001f', 3)        // field sep
        if (parts.length >= 3) {
            commits.add([hash: parts[0], author: parts[1], summary: parts[2]])
        }
    }
    if (commits.isEmpty()) {
        echo "No change entries after parsing for range: ${range}"
        return null
    }

    // 4) Header/file init
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    // 5) Repo URL for links
    String repoUrl = env.GIT_URL?.trim()
    if (!repoUrl) {
        repoUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    }
    if (repoUrl?.endsWith('.git')) repoUrl = repoUrl[0..-5]

    // 6) Compose the block
    String timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    StringBuilder block = new StringBuilder()
    block.append("## ").append(version).append(" (").append(timestamp).append(" UTC)\n")

    LinkedHashSet<String> authors = new LinkedHashSet<>()
    for (Map e : commits) {
        String h = (e.hash ?: '')
        String shortH = h.length() >= 7 ? h.substring(0, 7) : h
        String link = repoUrl ? "[[${shortH}](${repoUrl}/commit/${h})]" : "[${shortH}]"
        block.append("- ").append(e.summary ?: '').append(' ').append(link).append('\n')
        if (e.author) authors.add(e.author)
    }
    block.append("\n### Authors:\n")
    for (String a : authors) block.append("- ").append(a).append('\n')

    // 7) Idempotency: don’t duplicate the same version section
    String existing = readFile(file: outputFile)
    if (existing.contains("## ${version} (")) {
        echo "Changelog: version ${version} already present, skipping append"
        return outputFile
    }

    echo block.toString()
    sh "printf '%s\\n' \"${block}\" >> '${outputFile}'"

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }
    return outputFile
}
