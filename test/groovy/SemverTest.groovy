import org.junit.Before
import org.junit.Test

class SemverTest extends BaseLibTest {

  String commitMessage
  String commitSha

  @Before
  void customSh() {
    // Override sh(Map) to simulate git outputs consumed by semver.groovy
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      def script = m.script as String
      shCalls << script
      def out = ''
      if (script.contains('git log -1 --pretty=%B')) {
        out = (commitMessage ?: 'chore: test') + '\n'
      } else if (script.contains('git rev-parse HEAD')) {
        out = (commitSha ?: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') + '\n'
      } else if (script.contains('git rev-parse --abbrev-ref HEAD')) {
        out = 'main\n'
      } else if (script.contains('git describe --tags')) {
        // Simulate no existing tags
        out = ''
      } else if (script.contains('git fetch')) {
        out = ''
      } else if (script.contains('git rev-list --count')) {
        out = '0\n'
      }
      if (m.returnStdout) return out
      if (m.returnStatus) return 0
      return 0
    })
  }

  private def runSemver(Map cfg = [:]) {
    def step = loadStep('semver')
    return step.call([tagOnRelease:false, pushTags:false] + cfg)
  }

  @Test
  void bumps_patch_by_default_from_zero() {
    commitMessage = 'chore: update docs'
    commitSha = '1111111111111111111111111111111111111111'
    def v = runSemver()
    assert v.version == '0.0.1'
    assert v.bump == 'patch'
    assert !v.isRelease
  }

  @Test
  void bumps_major_when_token_present() {
    commitMessage = 'feat: new core impl !major'
    commitSha = '2222222222222222222222222222222222222222'
    def v = runSemver()
    assert v.version == '1.0.0'
    assert v.bump == 'major'
  }

  @Test
  void forced_minor_bump_overrides_commit_message() {
    commitMessage = 'chore: refactor only'
    commitSha = '3333333333333333333333333333333333333333'
    def v = runSemver(forceMinor:true)
    assert v.version == '0.1.0'
    assert v.bump == 'minor'
    assert v.forcedBump == 'minor'
  }

  @Test
  void detects_release_without_tagging_when_tagOnRelease_false() {
    commitMessage = 'fix: bug fix !release'
    commitSha = '4444444444444444444444444444444444444444'
    def v = runSemver()
    assert v.isRelease
    assert v.version == '0.0.1' // patch bump still happens
  }

  @Test
  void skips_second_run_same_commit_without_forced_bump() {
    commitMessage = 'chore: noop'
    commitSha = '5555555555555555555555555555555555555555'
    def first = runSemver()
    def second = runSemver()
    assert first.version == '0.0.1'
    assert second.bump == 'none'
    assert second.version == first.version
  }
}
