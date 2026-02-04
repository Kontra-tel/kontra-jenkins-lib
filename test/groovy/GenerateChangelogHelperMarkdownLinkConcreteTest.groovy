import kontra.jenkins.lib.GenerateChangelogHelper
import kontra.jenkins.lib.ChangelogUtils
import org.junit.Test
import static org.junit.Assert.*

class GenerateChangelogHelperMarkdownLinkConcreteTest {
    static class DummyScript {
        def env = [GIT_URL: 'https://github.com/org/repo']
        def sh(Map args) { return args.scriptResult ?: '' }
        def fileExists(String f) { false }
        def writeFile(Map args) { this.lastWrite = args; this.writeFileCalled = true }
        def readFile(Map args) { return '' }
        def echo(String s) { System.err.println("ECHO: $s") }
        Map lastWrite = [:]
        boolean writeFileCalled = false
    }

    @Test
    void testMarkdownLinkNeverSplitByNewline() {
        def script = new DummyScript()
        def helper = new GenerateChangelogHelper(script)
        // Simulate a commit message with a markdown link and newlines in the message
        def commitMsg = "feat: add feature with link [see docs](https://example.com/foo/bar)\nMore details here."
        def commits = [[
            hash: 'abc1234def5678',
            shortHash: 'abc1234',
            author: 'Alice',
            message: commitMsg
        ]]
        // Patch groupCommitsByType to just return our commit under Features
        helper.metaClass.static.groupCommitsByType = { List<Map> c -> [
            'Features': c,
            'Bug Fixes': [],
            'Documentation': [],
            'Performance': [],
            'Refactoring': [],
            'Other': []
        ]}
        // Patch cleanCommitMessage to identity
        helper.metaClass.static.cleanCommitMessage = { String m -> m }
        // Run
        helper.call([
            outputFile: 'test.md',
            commits: commits // inject directly for test
        ])
        if (!script.writeFileCalled) {
            fail('writeFile should have been called')
        }
        def written = script.lastWrite?.text
        if (written == null) {
            System.err.println('Changelog output is null!')
            fail('Changelog output should not be null')
        }
        // Find the markdown link line
        def linkLine = written.readLines().find { it.contains('https://github.com/org/repo/commit/abc1234def5678') }
        if (!linkLine) {
            System.err.println('Changelog output:\n' + written)
        }
        assertNotNull('Should find a markdown link line', linkLine)
        assertTrue('Markdown link line should not contain a newline in the link', !(linkLine =~ /\]\([^\)]*\n[^\)]*\)/))
        assertTrue('Markdown link line should be a single line', !linkLine.contains('\n'))
        assertTrue('Markdown link line should contain the correct URL', linkLine.contains('https://github.com/org/repo/commit/abc1234def5678'))
    }
}
