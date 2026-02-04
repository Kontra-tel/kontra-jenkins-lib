package kontra.jenkins.lib

class ChangelogUtils {
    /**
     * Joins lines for the first changelog entry line, handling multi-line markdown links.
     * Returns a map: [firstLine: String, nextIndex: int]
     */
    static Map joinFirstLineWithMarkdownLink(String[] msgLines) {
        StringBuilder firstLine = new StringBuilder()
        int i = 0
        // Skip leading blank lines
        while (i < msgLines.length && msgLines[i].trim() == '') i++
        if (i >= msgLines.length) return [firstLine: '', nextIndex: i]
        firstLine.append(msgLines[i].trim())
        i++
        // Join lines if markdown link is split across lines
        boolean foundOpenBracket = false
        boolean foundCloseBracket = false
        boolean foundOpenParen = false
        boolean foundCloseParen = false
        while (true) {
            String s = firstLine.toString()
            int lastOpenBracket = s.lastIndexOf('[')
            int lastCloseBracket = s.lastIndexOf(']')
            int lastOpenParen = s.lastIndexOf('(')
            int lastCloseParen = s.lastIndexOf(')')
            foundOpenBracket = lastOpenBracket != -1
            foundCloseBracket = lastCloseBracket != -1 && lastCloseBracket > lastOpenBracket
            foundOpenParen = lastOpenParen != -1 && lastOpenParen > lastCloseBracket
            foundCloseParen = lastCloseParen != -1 && lastCloseParen > lastOpenParen
            // If we have ([...](... but no closing )), join more lines
            if (foundOpenBracket && foundCloseBracket && foundOpenParen && !foundCloseParen) {
                if (i >= msgLines.length) break
                firstLine.append(msgLines[i].trim())
                i++
                continue
            }
            // If we have ([... but no ] or (, join more lines
            if ((foundOpenBracket && (!foundCloseBracket || lastOpenParen < lastCloseBracket)) || (foundOpenParen && !foundCloseParen)) {
                if (i >= msgLines.length) break
                firstLine.append(msgLines[i].trim())
                i++
                continue
            }
            // If we have an open [ but not yet a close ), and there is a ( but not a ), keep joining
            if (foundOpenBracket && foundOpenParen && !foundCloseParen) {
                if (i >= msgLines.length) break
                firstLine.append(msgLines[i].trim())
                i++
                continue
            }
            // If the line ends with an incomplete markdown link (e.g. ([...), join more lines
            if (s =~ /\(\[[^\]]*$/ || s =~ /\[[^\]]*$/ || s =~ /\([^\)]*$/) {
                if (i >= msgLines.length) break
                firstLine.append(msgLines[i].trim())
                i++
                continue
            }
            break
        }
        return [firstLine: firstLine.toString(), nextIndex: i]
    }
}
