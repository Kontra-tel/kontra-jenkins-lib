import org.junit.Test

class WriteEnvFileTest extends BaseLibTest {

  @Test
  void quotes_and_sorts_keys_and_escapes_newlines() {
    def step = loadStep('writeEnvFile')

    def res = step.call(
      dryRun: true,
      path: '/tmp/app.env',
      keys: ['BAR','FOO'],
      data: [FOO: 'a "quote"', BAR: 'line1\nline2'],
      mode: '600',
      owner: 'kaappi',
      group: 'kaappi'
    )

    assert res.dryRun
    assert res.path == '/tmp/app.env'
    assert res.keys == ['BAR','FOO'] // sorted by name inside the step if you enabled sorting
    assert res.content.contains('BAR="line1\\nline2"')
    assert res.content.contains('FOO="a \\"quote\\""')
    // no install calls when dryRun
    assert shCalls.find { it.contains('install -m') } == null
  }

  @Test
  void installs_with_mode_owner_group() {
    def step = loadStep('writeEnvFile')
    // Disable dryRun to see the install command being built
    step.call(
      dryRun: false,
      useSudo: false,
      path: '/tmp/app.env',
      keys: ['A'],
      data: [A: '1'],
      mode: '640',
      owner: 'kaappi',
      group: 'kaappi'
    )
    def installCmd = shCalls.find { it =~ /install -m 640 -o kaappi -g kaappi .* \/tmp\/app\.env/ }
    assert installCmd != null
  }
}
