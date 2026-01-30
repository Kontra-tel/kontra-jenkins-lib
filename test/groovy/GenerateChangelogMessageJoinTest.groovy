import groovy.test.GroovyTestCase
import kontra.jenkins.lib.ChangelogUtils

class GenerateChangelogMessageJoinTest extends GroovyTestCase {
    void testMultiLineMarkdownLinkIsJoined() {
        // Simulate a commit message with a markdown link split across lines
        String message = '''Add kontraInit: Implement CI bootstrap script to display shared library metadata and job details ([\nc9ef0b](https://github.com/kontra-tel/kontra-jenkins-lib/commit/\nc9ef0b8))'''

        String[] msgLines = message.split('\n')
        def joinResult = ChangelogUtils.joinFirstLineWithMarkdownLink(msgLines)
        String firstLine = joinResult.firstLine
        // The joined firstLine should contain the full markdown link
        assert firstLine.contains('([c9ef0b](https://github.com/kontra-tel/kontra-jenkins-lib/commit/c9ef0b8))')
    }
}
