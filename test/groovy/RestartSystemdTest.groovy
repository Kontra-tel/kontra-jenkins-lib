import org.junit.Test

class RestartSystemdTest extends BaseLibTest {

  @Test
  void does_not_use_sudo_by_default() {
    def step = loadStep('restartSystemd')
    step.call(service: 'demo')
    // Ensure commands are user-unit only and without sudo
    assert shCalls.find { it.contains('systemctl --user daemon-reload') }
    assert shCalls.find { it.contains('systemctl --user enable demo') }
    assert shCalls.find { it.contains('systemctl --user stop demo') }
    assert shCalls.find { it.contains('systemctl --user start demo') }
    assert shCalls.find { it.contains('systemctl --user status --no-pager demo') }
    assert !shCalls.any { it.contains('sudo ') }
  }

  @Test
  void does_not_use_sudo_even_with_runAsUser() {
    def step = loadStep('restartSystemd')
    step.call(service: 'demo', runAsUser: 'kontra-service')
    // Still no sudo; runAsUser is informational for path derivation only
    assert shCalls.find { it.contains('systemctl --user daemon-reload') }
    assert shCalls.find { it.contains('systemctl --user enable demo') }
    assert shCalls.find { it.contains('systemctl --user stop demo') }
    assert shCalls.find { it.contains('systemctl --user start demo') }
    assert shCalls.find { it.contains('systemctl --user status --no-pager demo') }
    assert !shCalls.any { it.contains('sudo ') }
  }
}
