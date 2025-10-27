# Jenkins Agent Setup (run as service user)

This guide shows how to run your Jenkins agent as the same Linux user that will own and run your services. This makes `deploySystemd` and `writeEnvFile` work without sudo.

## Prerequisites (on the agent host)

1. Create or use a dedicated user (example: `kontra.tel`).
2. Install Java (Temurin 21 or compatible):

   ```bash
   java -version
   ```

3. Enable systemd user lingering for the agent user so user services can run outside a login session:

   ```bash
   sudo loginctl enable-linger <agent-user>
   ```

4. Prepare directories owned by the agent user:

   ```bash
   # as the agent user
   mkdir -p "$HOME/apps"
   mkdir -p "$HOME/.config/systemd/user"
   ```

## Option A: SSH Agent (controller connects via SSH)

Use this when your controller can reach the agent over SSH and you want Jenkins to manage agent processes.

1. Create an SSH keypair for the agent user (if needed) and put the public key on the agent host.
2. In Jenkins: Manage Jenkins → Nodes → New Node
   - Name: `linux-appuser-01`
   - Remote root directory: `/home/<agent-user>`
   - Labels: `linux-agent-running-as-appuser`
   - Launch method: Launch agents via SSH
   - Host: `<agent-hostname>`
   - Credentials: SSH credentials for `<agent-user>`
3. Save and connect. Verify the node is online.

## Option B: Inbound Agent (agent connects to controller)

Use this when the agent initiates the connection to Jenkins (common behind firewalls/NAT).

1. In Jenkins: Manage Jenkins → Nodes → New Node
   - Name: `linux-appuser-01`
   - Remote root directory: `/home/<agent-user>/agent`
   - Launch method: Launch agent by connecting it to the controller
   - Copy the agent secret/URL (or use WebSocket mode)
2. On the agent host as `<agent-user>`:

   ```bash
   mkdir -p "$HOME/agent"
   curl -fsSL -o "$HOME/agent/agent.jar" "https://<jenkins-url>/jnlpJars/agent.jar"
   ```

3. Create a user systemd unit to run the agent:

   ```ini
   # $HOME/.config/systemd/user/jenkins-agent.service
   [Unit]
   Description=Jenkins Inbound Agent
   After=network-online.target

   [Service]
   Type=simple
   WorkingDirectory=%h/agent
   ExecStart=/usr/bin/java -jar %h/agent/agent.jar -url https://<jenkins-url> -secret <secret> -name linux-appuser-01 -workDir %h/agent
   Restart=always
   RestartSec=5
   StandardOutput=journal
   StandardError=journal

   [Install]
   WantedBy=default.target
   ```

4. Start and enable the user unit:

   ```bash
   systemctl --user daemon-reload
   systemctl --user enable --now jenkins-agent
   systemctl --user status jenkins-agent --no-pager
   ```

Tip: For WebSocket mode, replace the ExecStart with `-webSocket -url https://<jenkins-url>` (omit `-jnlpUrl`).

## Verify the environment

Run a simple pipeline on this node:

```groovy
pipeline {
  agent { label 'linux-agent-running-as-appuser' }
  stages {
    stage('Who am I') {
      steps {
        sh 'whoami && echo HOME=$HOME && systemctl --user is-active default.target || true'
      }
    }
    stage('Smoke deploy') {
      steps {
        writeEnvFile(path: "$HOME/apps/demo/.env", useSudo: false, data: [PORT: '8080'])
        deploySystemd(service: 'demo', artifactGlob: 'build/libs/*.jar')
      }
    }
  }
}
```

## Notes

- Use `$HOME/apps/<service>` paths to avoid permission issues (no `/opt` unless owned by the agent user).
- `writeEnvFile` should be called with `useSudo: false` and without `owner`/`group` when running as the agent user.
- Ensure Java is available on PATH for both the Jenkins agent and your services.
