import kontra.jenkins.lib.GenerateChangelogHelper
import kontra.jenkins.lib.ChangelogUtils
import org.junit.Test
import static org.junit.Assert.*

class GenerateChangelogHelperMarkdownLinkTest {
    static class DummyScript {
        def env = [:]
        def sh(Map args) { return args.scriptResult ?: '' }
        def fileExists(String f) { false }
        def writeFile(Map args) { }
        def readFile(Map args) { '' }
        def echo(String s) { }
    }

    @Test
    void testMarkdownLinkIsSingleLine() {
        def script = new DummyScript()
        def helper = new GenerateChangelogHelper(script)
        def commitMsg = "feat: add feature with link [see docs](https://example.com\nfoo\nbar)\nMore details here."
        def commits = [[
            hash: 'abc1234def5678',
            shortHash: 'abc1234',
            author: 'Alice',
            message: commitMsg
        ]]
        def grouped = GenerateChangelogHelper.groupCommitsByType(commits)
        def repoUrl = 'https://github.com/org/repo'
        def msgLines = commitMsg.split('\n')
        def joinResult = ChangelogUtils.joinFirstLineWithMarkdownLink(msgLines)
        String firstLine = joinResult.firstLine.replaceAll(/[\r\n]+/, ' ').trim()
        String link = "[abc1234](${repoUrl}/commit/abc1234def5678)".replaceAll(/[\r\n]+/, '')
        String markdown = "- **${firstLine}** (${link})"
        assertFalse('Markdown link should not contain newlines', markdown.contains('\n'))
        assertTrue('Markdown should contain the correct URL', markdown.contains('https://github.com/org/repo/commit/abc1234def5678'))
    }
}
