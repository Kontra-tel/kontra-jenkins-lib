import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before

abstract class BaseLibTest extends BasePipelineTest {
  List<String> shCalls = []
  Map<String,String> writtenFiles = [:]
  Map<String,String> readFiles = [:]

  @Before
  void setUpBase() {
    super.setUp()
    // Give steps an env map
    binding.setVariable('env', [:])

    // Stubs for common Jenkins steps
    helper.registerAllowedMethod('echo', [String.class], { s -> println "echo> $s" })
    helper.registerAllowedMethod('error', [String.class], { s -> throw new RuntimeException(s) })
    helper.registerAllowedMethod('fileExists', [String.class], { path -> writtenFiles.containsKey(path) || readFiles.containsKey(path) })
    helper.registerAllowedMethod('readFile', [Map.class], { m ->
      // Prefer previously written test files if present
      return readFiles.containsKey(m.file) ? readFiles[m.file] : (writtenFiles[m.file] ?: "")
    })
    helper.registerAllowedMethod('writeFile', [Map.class], { m -> writtenFiles[m.file] = m.text })

    // Two 'sh' overloads used by many steps
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      shCalls << (m.script as String)
      return m.returnStdout ? "" : 0
    })
    helper.registerAllowedMethod('sh', [String.class], { s ->
      shCalls << s
      return 0
    })
  }

  def loadStep(String name) { loadScript("vars/${name}.groovy") }
}
