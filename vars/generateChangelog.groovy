// vars/generateChangelog.groovy
def call(Map cfg = [:]) {
    String outputFile  = (cfg.outputFile ?: 'CHANGELOG.md') as String
    String plain    // 7) Idempotency: don't duplicate the same version section
    String existing = readFile(file: outputFile)
    if (existing.contains("## ${version}")) {
        echo "Changelog: version ${version} already present, skipping append"
        return outputFile
    }

    echo mdBlock.toString()
    sh "printf '%s\\n' \"${mdBlock}\" >> '${outputFile}'"

    // 8) Generate plain text version if requested
    if (plainOutput) {
        if (!fileExists(plainOutput) || (env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
            writeFile file: plainOutput, text: "CHANGELOG\n"
        }
        
        String plainExisting = readFile(file: plainOutput)
        if (!plainExisting.contains("${version}\n")) {
            sh "printf '%s\\n' \"${plainBlock}\" >> '${plainOutput}'"
            echo "Plain changelog written to: ${plainOutput}"
        }
    }

    if (appendTo) {
        sh "cat '${outputFile}' > '${appendTo}'"
    }
    return outputFile
}lainOutput ?: null) as String  // Optional plain text output
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
    // %B = full commit message (subject + body)
    String fmt = "%H%x1f%an%x1f%B%x1e"
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
            // Clean the message: remove tokens and trim
            String message = cleanCommitMessage(parts[2])
            if (message) {  // Only add if there's content after cleaning
                commits.add([hash: parts[0], author: parts[1], message: message])
            }
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

    // 6) Compose the markdown block
    String timestamp = new Date().format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC'))
    StringBuilder mdBlock = new StringBuilder()
    StringBuilder plainBlock = new StringBuilder()
    
    // Markdown version with better formatting
    mdBlock.append("\n---\n\n")
    mdBlock.append("## ").append(version).append("\n\n")
    mdBlock.append("**Released:** ").append(timestamp).append(" UTC\n\n")
    
    // Plain text version
    plainBlock.append("\n").append("=".multiply(60)).append("\n")
    plainBlock.append(version).append("\n")
    plainBlock.append("Released: ").append(timestamp).append(" UTC\n")
    plainBlock.append("=".multiply(60)).append("\n\n")

    LinkedHashSet<String> authors = new LinkedHashSet<>()
    
    // Group commits by type if they follow conventional commits pattern
    Map<String, List<Map>> grouped = groupCommitsByType(commits)
    
    for (String type : ['Features', 'Bug Fixes', 'Documentation', 'Performance', 'Refactoring', 'Other']) {
        List<Map> typeCommits = grouped[type]
        if (!typeCommits || typeCommits.isEmpty()) continue
        
        // Markdown section header
        mdBlock.append("### ").append(type).append("\n\n")
        
        // Plain text section header
        plainBlock.append(type).append(":\n")
        plainBlock.append("-".multiply(type.length())).append("\n")
        
        for (Map e : typeCommits) {
            String h = (e.hash ?: '')
            String shortH = h.length() >= 7 ? h.substring(0, 7) : h
            String link = repoUrl ? "[${shortH}](${repoUrl}/commit/${h})" : shortH
            
            // Format multi-line messages
            String msg = (e.message ?: '').trim()
            String[] lines = msg.split('\n')
            
            // Markdown: First line as main bullet with commit link
            mdBlock.append("- **").append(lines[0]).append("**")
                   .append(" (").append(link).append(")\n")
            
            // Plain text: First line with short hash
            plainBlock.append("  * ").append(lines[0])
                      .append(" [").append(shortH).append("]\n")
            
            // Additional lines as sub-items (indented)
            if (lines.length > 1) {
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i].trim()
                    if (line) {  // Skip empty lines
                        mdBlock.append("  - ").append(line).append('\n')
                        plainBlock.append("    - ").append(line).append('\n')
                    }
                }
            }
            
            if (e.author) authors.add(e.author)
        }
        
        mdBlock.append("\n")
        plainBlock.append("\n")
    }
    
    // Authors section
    mdBlock.append("### Contributors\n\n")
    plainBlock.append("Contributors:\n")
    plainBlock.append("-".multiply(12)).append("\n")
    
    for (String a : authors) {
        mdBlock.append("- ").append(a).append('\n')
        plainBlock.append("  - ").append(a).append('\n')
    }
    
    mdBlock.append("\n")
    plainBlock.append("\n")

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

/**
 * Clean commit message by removing tokens and extra whitespace
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
        
        if (firstLine =~ /^feat(\(.*?\))?:/ || firstLine.contains('add') || firstLine.contains('new')) {
            grouped['Features'].add(commit)
        } else if (firstLine =~ /^fix(\(.*?\))?:/ || firstLine.contains('fix') || firstLine.contains('bug')) {
            grouped['Bug Fixes'].add(commit)
        } else if (firstLine =~ /^docs(\(.*?\))?:/ || firstLine.contains('doc') || firstLine.contains('readme')) {
            grouped['Documentation'].add(commit)
        } else if (firstLine =~ /^perf(\(.*?\))?:/ || firstLine.contains('performance') || firstLine.contains('optim')) {
            grouped['Performance'].add(commit)
        } else if (firstLine =~ /^refactor(\(.*?\))?:/ || firstLine.contains('refactor') || firstLine.contains('cleanup')) {
            grouped['Refactoring'].add(commit)
        } else {
            grouped['Other'].add(commit)
        }
    }
    
    return grouped
}
