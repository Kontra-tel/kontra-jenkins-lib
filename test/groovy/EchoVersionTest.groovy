import org.junit.Before
import org.junit.Test

class EchoVersionTest extends BaseLibTest {

  @Before
  void stubs() {
    // Stub sh(Map) to simulate git describe when needed
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      shCalls << (m.script as String)
      def script = m.script as String
      if (script.contains('git describe --tags')) {
        return m.returnStdout ? 'v1.2.3\n' : 0
      }
      return m.returnStdout ? '' : 0
    })
    // echo already stubbed in BaseLibTest
  }

  @Test
  void returns_env_build_version_when_present() {
    def step = loadStep('echoVersion')
    binding.getVariable('env').BUILD_VERSION = '3.4.5'
    def v = step.call([:])
    assert v == '3.4.5'
  }

  @Test
  void falls_back_to_version_txt() {
    def step = loadStep('echoVersion')
    readFiles['version.txt'] = '2.0.1\n'
    def v = step.call([silent: true])
    assert v == '2.0.1'
  }

  @Test
  void falls_back_to_tag_when_no_env_or_file() {
    def step = loadStep('echoVersion')
    def v = step.call([silent: true])
    assert v == '1.2.3'
  }
}
