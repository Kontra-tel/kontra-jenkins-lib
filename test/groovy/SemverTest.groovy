import org.junit.Before
import org.junit.Test

class SemverTest extends BaseLibTest {

  String commitMessage
  String commitSha
  // Mocks for git interactions
  String mockDescribeTagOut = ''           // output for `git describe --tags ...`
  String mockTagListLatestOut = ''         // output for `git ... tag -l ... --sort=-v:refname | head -n1`
  int mockRevListCount = 0                 // output for `git rev-list --count <tag>..HEAD`

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
        // Nearest tag when requested
        out = (mockDescribeTagOut ?: '')
      } else if (script.contains('git fetch')) {
        out = ''
      } else if (script.contains('git rev-list --count')) {
        out = "${mockRevListCount}\n"
      } else if (script.contains(' tag -l ')) {
        // Latest tag selection path
        out = (mockTagListLatestOut ?: '') + '\n'
      }
      if (m.returnStdout) return out
      if (m.returnStatus) return 0
      return 0
    })
  }

  private def runSemver(Map cfg = [:]) {
    def step = loadStep('semver')
    // Ensure legacy expectation: default behavior auto-patch bumps unless caller overrides defaultBump
    if (!cfg.containsKey('defaultBump')) {
      cfg.defaultBump = 'patch'
    }
    def v = step.call([tagOnRelease:false, pushTags:false] + cfg)
    println "TEST: result=${v}"
    return v
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

  @Test
  void no_bump_when_default_none_and_no_tokens() {
    commitMessage = 'docs: update readme'
    commitSha = '6666666666666666666666666666666666666666'
    def v = runSemver(defaultBump:'none')
    assert v.version == '0.0.0'
    assert v.bump == 'none'
  }

  @Test
  void patch_bump_with_explicit_patch_token_when_default_none() {
    commitMessage = 'fix: minor fix !patch'
    commitSha = '7777777777777777777777777777777777777777'
    def v = runSemver(defaultBump:'none')
    assert v.version == '0.0.1'
    assert v.bump == 'patch'
  }

  @Test
  void returns_same_version_when_tag_strategy_no_tags_and_default_none() {
    // Simulate an existing version.txt with 1.2.3
    readFiles['version.txt'] = '1.2.3\n'
    // No tokens in commit message and no tags present (as mocked in customSh)
    commitMessage = 'chore: update pipeline'
    commitSha = '8888888888888888888888888888888888888888'
    def v = runSemver(strategy: 'tag', defaultBump: 'none')
    assert v.baseVersion == '1.2.3'
    assert v.version == '1.2.3'
    assert v.bump == 'none'
    assert v.baselineSource == 'file'
  }

  @Test
  void strict_tag_baseline_no_tags_default_none() {
    // No tags, no version.txt
    mockDescribeTagOut = ''
    commitMessage = 'chore: nothing'
    commitSha = '9999999999999999999999999999999999999999'
    def v = runSemver(strategy: 'tag', strictTagBaseline: true, defaultBump: 'none')
    assert v.baseVersion == '0.0.0'
    assert v.version == '0.0.0'
    assert v.bump == 'none'
    assert v.baselineSource == 'tag'
  }

  @Test
  void strict_tag_baseline_no_tags_default_patch() {
    mockDescribeTagOut = ''
    commitMessage = 'chore: bump'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0'
    def v = runSemver(strategy: 'tag', strictTagBaseline: true, defaultBump: 'patch')
    assert v.baseVersion == '0.0.0'
    assert v.version == '0.0.1'
    assert v.bump == 'patch'
    assert v.baselineSource == 'tag'
  }

  @Test
  void strict_tag_baseline_uses_tag_when_present() {
    mockDescribeTagOut = 'v1.2.3\n'
    commitMessage = 'chore: no bump'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1'
    def v = runSemver(strategy: 'tag', strictTagBaseline: true, defaultBump: 'none')
    assert v.baseVersion == '1.2.3'
    assert v.version == '1.2.3'
    assert v.bump == 'none'
    assert v.baselineSource == 'tag'
  }

  @Test
  void tag_mode_latest_selects_latest_tag() {
    mockTagListLatestOut = 'v2.0.0'
    commitMessage = 'chore: default patch'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2'
    def v = runSemver(strategy: 'tag', tagMode: 'latest')
    assert v.baseVersion == '2.0.0'
    assert v.version == '2.0.1'
    assert v.bump == 'patch'
    assert v.baselineSource == 'tag'
  }

  @Test
  void cumulative_patch_adds_commits_since_tag() {
    mockDescribeTagOut = 'v0.1.0\n'
    mockRevListCount = 3
    commitMessage = 'fix: small patch'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3'
    def v = runSemver(strategy: 'tag', cumulativePatch: true)
    assert v.baseVersion == '0.1.0'
    // origPatch=0 then +3 commits => 0.1.3
    assert v.version == '0.1.3'
    assert v.bump == 'patch'
    assert v.commitsSinceTag == 3
    assert v.baselineSource == 'tag'
  }

  @Test
  void file_strategy_bumps_from_version_txt() {
    readFiles['version.txt'] = '1.2.3\n'
    commitMessage = 'chore: routine'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4'
    def v = runSemver(strategy: 'file')
    assert v.baseVersion == '1.2.3'
    assert v.version == '1.2.4'
    assert v.bump == 'patch'
    assert v.baselineSource == 'file'
  }

  @Test
  void sanitize_noisy_file_version() {
    readFiles['version.txt'] = 'version: 9.8.7\n'
    commitMessage = 'chore: no bump'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa5'
    def v = runSemver(strategy: 'file', defaultBump: 'none')
    assert v.baseVersion == '9.8.7'
    assert v.version == '9.8.7'
    assert v.bump == 'none'
    assert v.baselineSource == 'file'
  }

  @Test
  void sanitize_noisy_tag_version() {
    mockDescribeTagOut = 'release-v3.4.5\n'
    commitMessage = 'chore: no bump'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa6'
    def v = runSemver(strategy: 'tag', strictTagBaseline: true, defaultBump: 'none')
    assert v.baseVersion == '3.4.5'
    assert v.version == '3.4.5'
    assert v.bump == 'none'
    assert v.baselineSource == 'tag'
  }

  @Test
  void cumulative_patch_does_not_apply_when_baseline_from_file() {
    // file has newer version than tag -> baselineSource should be 'file'
    readFiles['version.txt'] = '1.2.3\n'
    mockDescribeTagOut = 'v1.2.0\n'
    mockRevListCount = 5
    commitMessage = 'fix: patch'
    commitSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa7'
    def v = runSemver(strategy: 'tag', cumulativePatch: true)
    assert v.baseVersion == '1.2.3'
    assert v.baselineSource == 'file'
    // Since baseline is file, cumulative patch must NOT apply
    assert v.version == '1.2.4'
    assert v.bump == 'patch'
    assert v.commitsSinceTag == 0
  }
}
