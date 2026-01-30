// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    String outputFile  = (cfg.outputFile ?: 'CHANGELOG.md') as String
    String plainOutput = (cfg.plainOutput ?: null) as String  // Optional plain text output
    String appendTo    = (cfg.copyTo ?: null) as String
    String resetToken  = (cfg.resetToken ?: '!resetLog') as String
    String title       = (cfg.title ?: '# Changelog') as String
    String version     = (cfg.version ?: (env.BUILD_VERSION ?: 'Unversioned')) as String
    String tagPattern  = (cfg.tagPattern ?: 'v[0-9]*') as String
    Integer maxCommits = (cfg.maxCommits ?: 0) as Integer // 0 = no limit

    // 1) Decide the log range
    String base = (cfg.since ?: '') as String
    if (!base?.trim()) {
        base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT ?: ''
    }
    if (!base?.trim()) {
        base = sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true",
                  returnStdout: true).trim()
    }
    String range = base?.trim() ? "${base}..HEAD" : "HEAD~50..HEAD"

    // 2) Pull commits with FULL message body
    // %B = full commit message (subject + body)
    String fmt = "%H%x1f%an%x1f%B%x1e"
    String limit = maxCommits > 0 ? "--max-count=${maxCommits}" : ""
    String raw = sh(
        script: "git log --no-merges ${limit} --format='${fmt}' ${range}",
        returnStdout: true
    ).trim()

    if (!raw) {
        echo "No change entries detected for range: ${range}"
        return outputFile
    }

    // 3) Parse commits and clean messages
    List<Map> commits = []
    for (String rec : raw.split('\\u001e')) {
        if (!rec) continue
        String[] parts = rec.split('\\u001f', 3)
        if (parts.length >= 3) {
            String message = cleanCommitMessage(parts[2])
            if (message) {
                commits.add([
                    hash: parts[0],
                    shortHash: parts[0].take(7),
                    author: parts[1],
                    message: message
                ])
            }
        }
    }
    
    if (commits.isEmpty()) {
        echo "No change entries after parsing for range: ${range}"
        return outputFile
    }

    // 4) Group commits by type
    Map<String, List<Map>> grouped = groupCommitsByType(commits)

    // 5) Header/file init
    if (!fileExists(outputFile) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
        writeFile file: outputFile, text: "${title}\n"
    }

    // 6) Repo URL for links
    String repoUrl = env.GIT_URL?.trim()
    if (!repoUrl) {
        repoUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    }
    if (repoUrl?.endsWith('.git')) repoUrl = repoUrl[0..-5]

    // 7) Compose markdown and plain text blocks
    String timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    StringBuilder mdBlock = new StringBuilder()
    StringBuilder plainBlock = new StringBuilder()
    LinkedHashSet<String> authors = new LinkedHashSet<>()

    // Markdown version with better formatting
    mdBlock.append("\n---\n\n")
    mdBlock.append("## ").append(version).append("\n\n")
    mdBlock.append("**Released:** ").append(timestamp).append(" UTC\n\n")
    
    // Plain text version
    plainBlock.append("\n").append("=" * 80).append("\n")
    plainBlock.append("VERSION: ").append(version).append("\n")
    plainBlock.append("Released: ").append(timestamp).append(" UTC\n")
    plainBlock.append("=" * 80).append("\n\n")

    // Generate sections for each commit type
    for (String type : ['Features', 'Bug Fixes', 'Documentation', 'Performance', 'Refactoring', 'Other']) {
        List<Map> typeCommits = grouped[type]
        if (!typeCommits || typeCommits.isEmpty()) continue
        
        // Markdown section header
        mdBlock.append("### ").append(type).append("\n\n")
        
        // Plain text section header
        plainBlock.append(type.toUpperCase()).append("\n")
        plainBlock.append("-" * type.length()).append("\n")
        
        for (Map e : typeCommits) {
            String h = e.hash ?: ''
            String shortH = e.shortHash ?: ''
            String msg = (e.message ?: '').trim()
            String[] msgLines = msg.split('\n')
            def joinResult = this.getClass().classLoader.loadClass('kontra.jenkins.lib.ChangelogUtils')
                .joinFirstLineWithMarkdownLink(msgLines)
            String firstLine = joinResult.firstLine
            int i = joinResult.nextIndex
            String rest = i < msgLines.length ? msgLines[i..(msgLines.length-1)].join('\n') : ''

            // Markdown: First line with commit link (hash always on same line)
            String link = repoUrl ? "[${shortH}](${repoUrl}/commit/${h})" : shortH
            mdBlock.append("- **").append(firstLine).append("** ")
                   .append("(").append(link).append(")\n")

            // Plain text: First line with short hash (hash always on same line)
            plainBlock.append("  * ").append(firstLine)
                      .append(" [").append(shortH).append("]\n")

            // Additional lines as sub-items (indented, never with hash)
            if (rest) {
                String[] lines = rest.split('\n')
                for (String line : lines) {
                    line = line.trim()
                    if (line) {
                        // Markdown: add dash prefix
                        mdBlock.append("    ").append(line).append('\n')
                        // Plain text: just indent
                        plainBlock.append("      ").append(line).append('\n')
                    }
                }
            }

            if (e.author) authors.add(e.author)
        }
        
        mdBlock.append("\n")
        plainBlock.append("\n")
    }
    
    // Contributors section
    mdBlock.append("**Contributors:** ")
    for (String a : authors) {
        mdBlock.append(a).append(', ')
    }
    mdBlock.delete(mdBlock.length() - 2, mdBlock.length()) // Remove trailing comma
    mdBlock.append("\n\n")
    
    plainBlock.append("-" * 80).append("\n")
    plainBlock.append("Contributors: ")
    for (String a : authors) {
        plainBlock.append(a).append(', ')
    }
    plainBlock.delete(plainBlock.length() - 2, plainBlock.length())
    plainBlock.append("\n").append("=" * 80).append("\n\n")

    // 8) Idempotency check
    String existing = readFile(file: outputFile)
    if (existing.contains("## ${version}")) {
        echo "Changelog: version ${version} already present, skipping append"
        return outputFile
    }

    // 9) Write markdown changelog
    echo mdBlock.toString()
    String mdContent = existing + mdBlock.toString()
    writeFile file: outputFile, text: mdContent

    // 10) Write plain text changelog if requested
    if (plainOutput) {
        if (!fileExists(plainOutput) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
            writeFile file: plainOutput, text: "CHANGELOG\n"
        }
        
        String plainExisting = readFile(file: plainOutput)
        if (!plainExisting.contains("VERSION: ${version}")) {
            String plainContent = plainExisting + plainBlock.toString()
            writeFile file: plainOutput, text: plainContent
            echo "Plain changelog written to: ${plainOutput}"
        }
    }

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }
    return outputFile
}

/**
 * Clean commit message by removing release tokens and extra whitespace
 */
private String cleanCommitMessage(String msg) {
    if (!msg) return ''
    
    // Remove common tokens (case insensitive)
    String cleaned = msg.replaceAll(/(?i)!release/, '')
                        .replaceAll(/(?i)!tag/, '')
                        .replaceAll(/(?i)!deploy/, '')
                        .replaceAll(/(?i)!major/, '')
                        .replaceAll(/(?i)!minor/, '')
                        .replaceAll(/(?i)!patch/, '')
                        .replaceAll(/(?i)!resetLog/, '')
    
    // Clean up multiple spaces/newlines and trim
    cleaned = cleaned.replaceAll(/[ ]+/, ' ')  // Multiple spaces to single
                    .replaceAll(/\n\n+/, '\n\n')  // Multiple newlines to double
                    .trim()
    
    return cleaned
}

/**
 * Group commits by type based on conventional commits or keywords
 */
private Map<String, List<Map>> groupCommitsByType(List<Map> commits) {
    Map<String, List<Map>> grouped = [
        'Features': [],
        'Bug Fixes': [],
        'Documentation': [],
        'Performance': [],
        'Refactoring': [],
        'Other': []
    ]
    
    for (Map commit : commits) {
        String msg = (commit.message ?: '').toLowerCase()
        String firstLine = msg.split('\n')[0]
        
        if (firstLine =~ /^feat(\(.*?\))?:/ || firstLine.contains('add') || firstLine.contains('new') || firstLine.contains('implement')) {
            grouped['Features'].add(commit)
        } else if (firstLine =~ /^fix(\(.*?\))?:/ || firstLine.contains('fix') || firstLine.contains('bug') || firstLine.contains('issue')) {
            grouped['Bug Fixes'].add(commit)
        } else if (firstLine =~ /^docs(\(.*?\))?:/ || firstLine.contains('doc') || firstLine.contains('readme')) {
            grouped['Documentation'].add(commit)
        } else if (firstLine =~ /^perf(\(.*?\))?:/ || firstLine.contains('performance') || firstLine.contains('optim') || firstLine.contains('speed')) {
            grouped['Performance'].add(commit)
        } else if (firstLine =~ /^refactor(\(.*?\))?:/ || firstLine.contains('refactor') || firstLine.contains('cleanup') || firstLine.contains('restructure')) {
            grouped['Refactoring'].add(commit)
        } else {
            grouped['Other'].add(commit)
        }
    }
    
    return grouped
}
