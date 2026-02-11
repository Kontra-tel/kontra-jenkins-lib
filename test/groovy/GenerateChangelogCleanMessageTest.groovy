import kontra.jenkins.lib.GenerateChangelogHelper
import org.junit.Test
import static org.junit.Assert.*

class GenerateChangelogCleanMessageTest {
    @Test
    void doesNotStripTokensInsideUrlsButStripsStandaloneFlags() {
        def original = "feat: add endpoint [docs](https://example.com/!deploy/path) !deploy !patch"
        def cleaned = GenerateChangelogHelper.cleanCommitMessage(original)
        // URL should remain intact
        assertTrue(cleaned.contains("https://example.com/!deploy/path"))
        // Standalone flags should be removed
        assertFalse(cleaned.contains(" !deploy"))
        assertFalse(cleaned.contains(" !patch"))
        // Core description still present
        assertTrue(cleaned.toLowerCase().contains("feat: add endpoint"))
    }
}
