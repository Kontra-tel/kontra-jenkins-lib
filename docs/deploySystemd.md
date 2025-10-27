# deploySystemd

Deploy services as systemd user units with automatic artifact deployment and script generation. Optimized for running on a Jenkins agent that already runs as the intended service user (no sudo required).

## Overview

`deploySystemd` simplifies deploying applications as systemd user services. It handles artifact deployment, script generation, and unit file creation — all running under user systemd (no root/sudo required). The recommended setup is to run your Jenkins agent as the same user that will own and run the service.

**Key Features:**

- User units only (secure, no sudo needed)
- Automatic artifact deployment from build outputs
- Launch script generation or use existing scripts
- Java application support with JVM options
- Clean defaults for Jenkins agents running as the service user
- Dry-run mode for testing

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent { label 'linux-agent-running-as-appuser' }
    stages {
        stage('Deploy') {
            steps {
                deploySystemd(
                    service: 'my-app',
                    artifactGlob: 'build/libs/*.jar'
                    // workingDir defaults to "$HOME/apps/<service>" for the agent user
                )
            }
        }
    }
}
```

## Parameters

### Required

- `service` (String) - Service name (used for unit file and systemctl commands)

### Common Options

- `workingDir` (String) - Working directory for the service (default: `$HOME/apps/<service>`)
- `description` (String) - Service description (default: `"Service <service>"`)
- `envFile` (String) - Path to environment file (optional)
- `runAsUser` (String) - Optional. Used only to derive home-based paths for unit and workingDir when you deploy on behalf of another user. No sudo/switching occurs in this step.

### Artifact Deployment

- `artifactGlob` (String) - Glob pattern for artifacts (e.g., `'build/libs/*.jar'`) - deploys latest match
- `targetDir` (String) - Where to install artifacts (default: `workingDir`)
- `targetName` (String) - Installed artifact name (default: `"<service>.jar"`)

### ExecStart Configuration

Three ways to define what runs (in priority order):

1. **Explicit Command**

   ```groovy
   execStart: "/usr/bin/java -jar /opt/app/app.jar"
   ```

2. **Start Command** (generates launch script)

   ```groovy
   startCommand: "java -jar app.jar --port 8080"
   ```

3. **Repo Launch Script** (copies existing script)

   ```groovy
   repoLaunchScript: 'launch.sh'  // Default - auto-detected if exists
   ```

4. **Auto-synthesized** (for Java apps when no script provided)

   ```groovy
   javaBin: '/usr/bin/java'        // Default
   javaOpts: '-Xmx512m'           // JVM options
   appArgs: '--port 8080'         // Application arguments
   jarPath: '/opt/app/app.jar'    // Or uses artifact from artifactGlob
   ```

### Unit File Options

- `installUnit` (Boolean) - Create/update unit file (default: `true`)
- `overwriteUnit` (Boolean) - Overwrite existing unit (default: `true`)
- `unitPath` (String) - Custom unit file path (default: `~/.config/systemd/user/<service>.service`)

### Service Behavior

- `restart` (String) - Restart policy: `'always'` (default) or `'on-failure'`
- `restartSec` (String) - Seconds between restarts (default: `'3'`)
- `enable` (Boolean) - Enable service to start automatically (default: `true`)

### Testing

- `dryRun` (Boolean) - Preview without making changes (default: `false`)

## Examples

### Simple Java Application

```groovy
deploySystemd(
    service: 'my-api',
    artifactGlob: 'target/*.jar',
    // workingDir defaults to $HOME/apps/my-api (no sudo required)
    javaOpts: '-Xmx1g -Xms512m',
    appArgs: '--port 8080'
)
```

### With Custom Launch Script

```groovy
// Project has launch.sh in repo root
deploySystemd(
    service: 'worker',
    // workingDir defaults to $HOME/apps/worker
    repoLaunchScript: 'scripts/start.sh'
)
```

### With Start Command

```groovy
deploySystemd(
    service: 'node-app',
    startCommand: 'npm start'
)
```

### With Environment File

```groovy
stage('Create Env') {
    writeEnvFile(
        path: "${env.HOME}/apps/app/.env",
        data: [PORT: '8080', DB_URL: 'jdbc:...']
    )
}
stage('Deploy') {
    deploySystemd(
        service: 'app',
        envFile: "${env.HOME}/apps/app/.env",
        artifactGlob: 'build/libs/*.jar'
    )
}
```

### Jenkins agent as the service user (recommended)

Run the Jenkins agent as the same user that owns the service. This avoids all sudo and permission complexity.

Prerequisites:

- loginctl enable-linger \<agent-user\>
- Ensure the agent user's home directory is present and writable
- Optional: pre-create `$HOME/apps` if you prefer a fixed structure

### Dry Run

```groovy
def preview = deploySystemd(
    service: 'test-svc',
    dryRun: true,
    artifactGlob: 'build/libs/*.jar',
    workingDir: '/opt/test-svc'
)

echo "Would create unit file:"
echo preview.unitContent
echo "Would execute: ${preview.execStart}"
```

## Return Values

```groovy
def result = deploySystemd(service: 'my-app', ...)

result.dryRun           // Boolean: was this a dry run?
result.service          // String: service name
result.unitPath         // String: path to unit file
result.unitWritten      // Boolean: was unit file created/updated?
result.unitContent      // String: unit file contents (always in dryRun)
result.deployedJar      // String: path to deployed jar (if artifactGlob used)
result.execStart        // String: the ExecStart command
result.launchScript     // String: path to launch script (if created)
result.launchScriptContent // String: launch script contents (dryRun only)
result.restarted        // Boolean: was service restarted?
result.restartResult    // Map: result from restartSystemd call
```

## How It Works

1. **Artifact Deployment** - If `artifactGlob` specified:
   - Find latest matching artifact
   - Copy to `targetDir/targetName`

2. **Script Generation** - Create launch script if needed:
   - From `startCommand` (generates bash script)
   - From `repoLaunchScript` (copies existing script)
   - Auto-synthesize Java command (if no script provided)

3. **Unit File Creation** - Generate systemd unit file:
    - `$HOME/.config/systemd/user/<service>.service` (HOME is derived from agent user or `runAsUser` only for path derivation)
    - Includes: WorkingDirectory, EnvironmentFile, ExecStart, Restart policy

4. **Service Management** - Calls [restartSystemd](./restartSystemd.md):
   - daemon-reload
   - enable (if requested)
   - stop → start
   - status check

## User Units vs System Units

This module **only supports user units** for security and simplicity:

- No sudo required
- Runs under specific user account
- Isolated from system services
- Safer for CI/CD deployments

For 24/7 operation, enable **lingering** for the agent user:
```bash
sudo loginctl enable-linger <username>
```

See [SYSTEMD_PERMISSIONS.md](../docs/SYSTEMD_PERMISSIONS.md) for details.

## Troubleshooting

### Service Won't Start

```bash
# Check service status
systemctl --user status my-app

# View logs
journalctl --user -u my-app -f

# For central service user
sudo -u kontra-service systemctl --user status my-app
sudo journalctl --user-unit my-app -f
```

### Unit File Not Found

```bash
# Check unit file exists
ls ~/.config/systemd/user/my-app.service

# Reload daemon
systemctl --user daemon-reload

# List all user units
systemctl --user list-units
```

### Permission Denied

If you see permission errors writing files or installing artifacts, ensure you are deploying under a writable path for the agent user (e.g., `$HOME/apps/<service>`). Do not use `/opt` unless that directory is owned by the agent user.

## Related

- [restartSystemd](./restartSystemd.md) - Restart existing services
- [writeEnvFile](./writeEnvFile.md) - Create environment files
- [CENTRAL_SERVICE_USER.md](../docs/CENTRAL_SERVICE_USER.md) - Central user setup
- [SYSTEMD_PERMISSIONS.md](../docs/SYSTEMD_PERMISSIONS.md) - Permission configurations
