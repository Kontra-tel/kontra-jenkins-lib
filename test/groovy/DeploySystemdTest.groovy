import org.junit.Before
import org.junit.Test

/** Tests for deploySystemd step (dryRun + unit synthesis). */
class DeploySystemdTest extends BaseLibTest {

  @Before
  void customShStubs() {
    // Override 'sh(Map)' to return a fake artifact for the ls command
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      shCalls << (m.script as String)
      def script = m.script as String
      if (script.contains("ls -t target/*.jar")) {
        return m.returnStdout ? "target/app-1.2.3.jar\n" : 0
      }
      return m.returnStdout ? "" : 0
    })
  }

  @Test
  void composes_unit_and_deploys_latest_artifact_in_dry_run() {
    def step = loadStep('deploySystemd')

    def res = step.call(
      dryRun: true,
      service: 'kontraAPI',
      description: 'Kontra API',
      workingDir: '/opt/kontraAPI',
      artifactGlob: 'target/*.jar',
      targetDir: '/opt/kontraAPI/build',
      targetName: 'kontraAPI.jar',
      envFile: '/opt/kontraAPI/build/.env',
      user: 'kaappi',
      group: 'kaappi',
      installUnit: true,
      overwriteUnit: true,
      useSudo: false,
      execStart: "/usr/bin/env bash -lc 'cd /opt/kontraAPI && ./startup.sh'"
    )

    assert res.dryRun
    assert res.service == 'kontraAPI'
  // unit content should be returned and staged at a .dryrun.* path only
  assert res.unitContent
  def unitText = res.unitContent
    assert unitText.contains('Description=Kontra API')
    assert unitText.contains('WorkingDirectory=/opt/kontraAPI')
    assert unitText.contains('EnvironmentFile=-/opt/kontraAPI/build/.env')
    assert unitText.contains("ExecStart=/usr/bin/env bash -lc 'cd /opt/kontraAPI && ./startup.sh'")

    // in dryRun we should not see daemon-reload/start
    assert shCalls.find { it.contains('systemctl') } == null
  }

  @Test
  void uses_user_unit_when_requested() {
    def step = loadStep('deploySystemd')
    def res = step.call(
      dryRun: true,
      service: 'myuser-svc',
      workingDir: '/home/jenkins/myapp',
      useUserUnit: true,     // <-- user unit
      installUnit: true,
      overwriteUnit: true,
      useSudo: false,
      execStart: "/usr/bin/env bash -lc 'echo hello'"
    )
  assert res.unitContent
  }

  @Test
  void synthesizes_execstart_with_java_opts_and_app_args() {
    def step = loadStep('deploySystemd')
    def res = step.call(
      dryRun: true,
      service: 'synthsvc',
      workingDir: '/opt/synthsvc',
      artifactGlob: 'target/*.jar',
      targetDir: '/opt/synthsvc',
      targetName: 'synthsvc.jar',
      javaOpts: '-Xmx256m',
      appArgs: '--port 8080 --verbose',
      user: 'root',
      group: 'root',
      installUnit: true,
      overwriteUnit: true,
      useSudo: false
    )
    assert res.dryRun
    assert res.execStart.contains('/usr/bin/java -Xmx256m -jar /opt/synthsvc/synthsvc.jar --port 8080 --verbose')
  assert res.unitContent.contains('ExecStart=/usr/bin/java -Xmx256m -jar /opt/synthsvc/synthsvc.jar --port 8080 --verbose')
  }
}
