package kontra.jenkins.lib

class ChangelogUtils {
    /**
     * Joins lines for the first changelog entry line, handling multi-line markdown links.
     * Returns a map: [firstLine: String, nextIndex: int]
     */
    static Map joinFirstLineWithMarkdownLink(String[] msgLines) {
        String firstLine = ''
        int i = 0
        while (i < msgLines.length && firstLine.trim() == '') {
            firstLine = msgLines[i].trim()
            i++
        }
        if (firstLine.endsWith("(") || firstLine.endsWith("[")) {
            while (i < msgLines.length) {
                firstLine += msgLines[i].trim()
                i++
                int lastCloseBracket = firstLine.lastIndexOf("]")
                int lastCloseParen = firstLine.lastIndexOf(")")
                if (lastCloseBracket != -1 && lastCloseParen != -1 && lastCloseParen > lastCloseBracket) {
                    break
                }
            }
        }
        return [firstLine: firstLine, nextIndex: i]
    }
}
