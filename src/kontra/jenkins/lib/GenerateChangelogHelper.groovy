package kontra.jenkins.lib

class GenerateChangelogHelper implements Serializable {
    def script

    GenerateChangelogHelper(script) {
        this.script = script
    }

    def call(Map cfg = [:]) {
        // Read config with defaults
        def outputFile  = cfg.outputFile ?: 'CHANGELOG.md'
        def plainOutput = cfg.plainOutput ?: null
        def appendTo    = cfg.copyTo ?: null
        def resetToken  = cfg.resetToken ?: '!resetLog'
        def title       = cfg.title ?: '# Changelog'
        def version     = cfg.version ?: (script.env.BUILD_VERSION ?: 'Unversioned')
        def tagPattern  = cfg.tagPattern ?: 'v[0-9]*'
        def maxCommits  = cfg.maxCommits ?: 0

        // TEST HOOK: allow direct commit injection for tests
        def commits = cfg.commits
        if (commits == null) {
            // 1) Decide the log range
            def base = cfg.since ?: ''
            if (!base?.trim()) base = script.env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: script.env.GIT_PREVIOUS_COMMIT ?: ''
            if (!base?.trim()) base = script.sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true", returnStdout: true).trim()
            def range = base?.trim() ? "${base}..HEAD" : "HEAD~50..HEAD"

            // 2) Pull commits with FULL message body
            def fmt = "%H%x1f%an%x1f%B%x1e"
            def limit = maxCommits > 0 ? "--max-count=${maxCommits}" : ""
            def raw = script.sh(script: "git log --no-merges ${limit} --format='${fmt}' ${range}", returnStdout: true).trim()
            if (!raw) {
                script.echo "No change entries detected for range: ${range}"
                return outputFile
            }

            // 3) Parse commits and clean messages
            commits = []
            raw.split('\u001e').each { rec ->
                if (!rec) return
                def parts = rec.split('\u001f', 3)
                if (parts.length >= 3) {
                    def message = cleanCommitMessage(parts[2])
                    if (message) {
                        commits << [hash: parts[0], shortHash: parts[0].take(7), author: parts[1], message: message]
                    }
                }
            }
            if (!commits) {
                script.echo "No change entries after parsing for range: ${range}"
                return outputFile
            }
        }

        // 4) Group commits by type
        def grouped = groupCommitsByType(commits)

        // 5) Header/file init
        if (!script.fileExists(outputFile) || (script.env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
            script.writeFile file: outputFile, text: "${title}\n"
        }

        // 6) Repo URL for links
        def repoUrl = script.env.GIT_URL?.trim() ?: script.sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
        if (repoUrl?.endsWith('.git')) repoUrl = repoUrl[0..-5]

        // 7) Compose markdown and plain text blocks
        // Use java.time for Java 21+ compatibility
        def timestamp
        try {
            def now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            def fmt = java.time.format.DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm')
            timestamp = now.format(fmt)
        } catch (Throwable t) {
            // Fallback for older Groovy/Java
            timestamp = new Date().format('yyyy-MM-dd HH:mm', java.util.TimeZone.getTimeZone('UTC'))
        }
        def mdBlock = new StringBuilder()
        def plainBlock = new StringBuilder()
        def authors = new LinkedHashSet<String>()

        mdBlock.append("\n---\n\n")
        mdBlock.append("## ").append(version).append("\n\n")
        mdBlock.append("**Released:** ").append(timestamp).append(" UTC\n\n")
        plainBlock.append("\n").append("=" * 80).append("\n")
        plainBlock.append("VERSION: ").append(version).append("\n")
        plainBlock.append("Released: ").append(timestamp).append(" UTC\n")
        plainBlock.append("=" * 80).append("\n\n")

        ['Features', 'Bug Fixes', 'Documentation', 'Performance', 'Refactoring', 'Other'].each { type ->
            def typeCommits = grouped[type]
            if (!typeCommits) return
            mdBlock.append("### ").append(type).append("\n\n")
            plainBlock.append(type.toUpperCase()).append("\n")
            plainBlock.append("-" * type.length()).append("\n")
            typeCommits.each { e ->
                def h = e.hash ?: ''
                def shortH = e.shortHash ?: ''
                def msg = (e.message ?: '').trim()
                def msgLines = msg.split('\n')
                def joinResult = ChangelogUtils.joinFirstLineWithMarkdownLink(msgLines)
                def firstLine = joinResult.firstLine.replaceAll(/[\r\n]+/, ' ').trim()
                def i = joinResult.nextIndex
                def rest = i < msgLines.length ? msgLines[i..-1].join('\n') : ''
                def link = repoUrl ? "[${shortH}](${repoUrl}/commit/${h})" : shortH
                link = link.replaceAll(/[\r\n]+/, '')
                mdBlock.append("- **").append(firstLine).append("** (").append(link).append(")\n")
                plainBlock.append("  * ").append(firstLine).append(" [").append(shortH).append("]\n")
                if (rest) {
                    rest.split('\n').each { line ->
                        line = line.trim()
                        if (line) {
                            mdBlock.append("    ").append(line).append('\n')
                            plainBlock.append("      ").append(line).append('\n')
                        }
                    }
                }
                if (e.author) authors << e.author
            }
            mdBlock.append("\n")
            plainBlock.append("\n")
        }
        mdBlock.append("**Contributors:** ")
        authors.each { a -> mdBlock.append(a).append(', ') }
        if (authors) mdBlock.delete(mdBlock.length() - 2, mdBlock.length())
        mdBlock.append("\n\n")
        plainBlock.append("-" * 80).append("\n")
        plainBlock.append("Contributors: ")
        authors.each { a -> plainBlock.append(a).append(', ') }
        if (authors) plainBlock.delete(plainBlock.length() - 2, plainBlock.length())
        plainBlock.append("\n").append("=" * 80).append("\n\n")

        def existing = script.readFile(file: outputFile)
        if (existing.contains("## ${version}")) {
            script.echo "Changelog: version ${version} already present, skipping append"
            return outputFile
        }
        script.echo mdBlock.toString()
        def mdContent = existing + mdBlock.toString()
        script.writeFile file: outputFile, text: mdContent
        if (plainOutput) {
            if (!script.fileExists(plainOutput) || (script.env.COMMIT_MESSAGE ?: '').contains(resetToken)) {
                script.writeFile file: plainOutput, text: "CHANGELOG\n"
            }
            def plainExisting = script.readFile(file: plainOutput)
            if (!plainExisting.contains("VERSION: ${version}")) {
                def plainContent = plainExisting + plainBlock.toString()
                script.writeFile file: plainOutput, text: plainContent
                script.echo "Plain changelog written to: ${plainOutput}"
            }
        }
        if (appendTo) {
            script.sh "cat '${outputFile}' > '${appendTo}'"
        }
        return outputFile
    }

    static String cleanCommitMessage(String msg) {
        if (!msg) return ''
        String cleaned = msg.replaceAll(/(?i)!release/, '')
                            .replaceAll(/(?i)!tag/, '')
                            .replaceAll(/(?i)!deploy/, '')
                            .replaceAll(/(?i)!major/, '')
                            .replaceAll(/(?i)!minor/, '')
                            .replaceAll(/(?i)!patch/, '')
                            .replaceAll(/(?i)!resetLog/, '')
        cleaned = cleaned.replaceAll(/[ ]+/, ' ')
                        .replaceAll(/\n\n+/, '\n\n')
                        .trim()
        return cleaned
    }

    static Map<String, List<Map>> groupCommitsByType(List<Map> commits) {
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
}
