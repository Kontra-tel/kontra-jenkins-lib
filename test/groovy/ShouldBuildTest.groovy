import org.junit.Before
import org.junit.Test

class ShouldBuildTest extends BaseLibTest {

  Map env
  def shouldBuild

  @Override
  @Before
  void setUp() {
    super.setUp()
    env = binding.getVariable('env') as Map
    shouldBuild = loadStep('shouldBuild')
  }

  private def runShouldBuild(Map cfg = [:]) {
    return shouldBuild.call(cfg)
  }

  @Test
  void shouldBuild_withRequiredToken_returnsTrue() {
    env.COMMIT_MESSAGE = 'Add feature !release'
    
    def result = runShouldBuild(requiredTokens: ['!release'], verbose: false)
    
    assert result == true
  }

  @Test
  void shouldBuild_withoutRequiredToken_returnsFalse() {
    env.COMMIT_MESSAGE = 'Just a regular commit'
    
    def result = runShouldBuild(requiredTokens: ['!release'], verbose: false)
    
    assert result == false
  }

  @Test
  void shouldBuild_withAnyToken_matchesAny() {
    env.COMMIT_MESSAGE = 'Add feature !tag'
    
    // Should match because it has !tag (even though !release is missing)
    def result = runShouldBuild(
      requiredTokens: ['!release', '!tag'],
      anyToken: true,
      verbose: false
    )
    
    assert result == true
  }

  @Test
  void shouldBuild_withAllTokensRequired_needsAll() {
    env.COMMIT_MESSAGE = 'Add feature !tag'
    
    // Should NOT match because !release is missing (requires ALL tokens)
    def result = runShouldBuild(
      requiredTokens: ['!release', '!tag'],
      anyToken: false,
      verbose: false
    )
    
    assert result == false
  }

  @Test
  void shouldBuild_withAllTokensPresent_returnsTrue() {
    env.COMMIT_MESSAGE = 'Add feature !minor !tag !release'
    
    // Should match because ALL tokens are present
    def result = runShouldBuild(
      requiredTokens: ['!release', '!tag'],
      anyToken: false,
      verbose: false
    )
    
    assert result == true
  }

  @Test
  void shouldBuild_withForce_ignoresTokens() {
    env.COMMIT_MESSAGE = 'Regular commit without tokens'
    
    // Should build because force=true
    def result = runShouldBuild(
      requiredTokens: ['!release'],
      force: true,
      verbose: false
    )
    
    assert result == true
  }

  @Test
  void shouldBuild_withEnvForce_ignoresTokens() {
    env.COMMIT_MESSAGE = 'Regular commit without tokens'
    env.FORCE_BUILD = 'true'
    
    // Should build because env.FORCE_BUILD=true
    def result = runShouldBuild(
      requiredTokens: ['!release'],
      verbose: false
    )
    
    assert result == true
  }

  @Test
  void shouldBuild_usesDefaultTokens() {
    env.COMMIT_MESSAGE = 'Update version !tag'
    
    // Default tokens are ['!tag', '!release'] with anyToken logic
    def result = runShouldBuild(verbose: false)
    
    // Should match because !tag is one of the default tokens (but it requires ALL by default)
    assert result == false  // Missing !release
  }

  @Test
  void shouldBuild_withMultipleTokensInCommit() {
    env.COMMIT_MESSAGE = 'Major update !major !tag !release !github-release'
    
    def result = runShouldBuild(
      requiredTokens: ['!tag', '!release'],
      verbose: false
    )
    
    assert result == true
  }

  @Test
  void shouldBuild_caseInsensitiveTokens() {
    env.COMMIT_MESSAGE = 'Add feature !RELEASE'
    
    // Tokens are case-sensitive by default (this is expected behavior)
    def result = runShouldBuild(
      requiredTokens: ['!release'],
      verbose: false
    )
    
    assert result == false  // Won't match !RELEASE vs !release
  }

  @Test
  void shouldBuild_withEnvCommitMessage() {
    env.COMMIT_MESSAGE = 'Feature update !release'
    
    // Should use env.COMMIT_MESSAGE without calling sh
    def result = runShouldBuild(
      requiredTokens: ['!release'],
      verbose: false
    )
    
    assert result == true
  }
}
