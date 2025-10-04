# restartSystemd

Manage existing systemd user services - reload, enable, restart, and check status.

## Overview

`restartSystemd` provides fine-grained control over systemd user services. Use this when you need to restart an already-deployed service without redeploying artifacts or regenerating unit files.

**Key Features:**
- Reload systemd daemon configuration
- Enable/disable services
- Stop and start services
- Status checking
- Central service user support
- Granular control over each operation

## Basic Usage

```groovy
// Simple restart
restartSystemd(service: 'my-app')

// Restart as central service user
restartSystemd(
    service: 'my-app',
    runAsUser: 'kontra-service'
)
```

## Parameters

### Required

- `service` (String) - Service name

### Optional

- `runAsUser` (String) - Run systemctl commands as different user (default: current user)
- `enable` (Boolean) - Enable service to start automatically (default: `true`)
- `reload` (Boolean) - Reload systemd daemon (default: `true`)
- `stop` (Boolean) - Stop service before starting (default: `true`)
- `start` (Boolean) - Start service (default: `true`)
- `showStatus` (Boolean) - Display service status (default: `true`)

## Examples

### Simple Restart

```groovy
@Library('kontra-jenkins-lib')

pipeline {
    agent any
    stages {
        stage('Restart Service') {
            steps {
                restartSystemd(service: 'my-api')
            }
        }
    }
}
```

### Restart Without Enabling

```groovy
// Restart but don't enable auto-start
restartSystemd(
    service: 'my-app',
    enable: false
)
```

### Reload Configuration Only

```groovy
// Just reload daemon, don't restart service
restartSystemd(
    service: 'my-app',
    stop: false,
    start: false
)
```

### Start Service (No Stop)

```groovy
// Start service without stopping first
restartSystemd(
    service: 'my-app',
    stop: false
)
```

### Stop Service Only

```groovy
// Stop service without starting
restartSystemd(
    service: 'my-app',
    start: false
)
```

### Central Service User

```groovy
// Restart service running under kontra-service user
restartSystemd(
    service: 'api',
    runAsUser: 'kontra-service'
)
```

### Quiet Restart

```groovy
// Don't show status output
restartSystemd(
    service: 'my-app',
    showStatus: false
)
```

## Return Values

```groovy
def result = restartSystemd(service: 'my-app')

result.service    // String: service name
result.runAsUser  // String: user commands were run as (null if current user)
result.enabled    // Boolean: was service enabled?
result.reloaded   // Boolean: was daemon reloaded?
result.stopped    // Boolean: was service stopped?
result.started    // Boolean: was service started?
```

## Operation Order

When all options are true (defaults), operations execute in this order:

1. `daemon-reload` - Reload systemd configuration
2. `enable` - Enable service for auto-start
3. `stop` - Stop the service gracefully
4. `start` - Start the service
5. `status` - Show service status (non-blocking)

## Use Cases

### After Configuration Changes

```groovy
// Updated environment file or config
writeEnvFile(path: '/opt/app/.env', data: newConfig)
restartSystemd(service: 'app')
```

### Deployment Without Artifact Changes

```groovy
// Just restart the service
restartSystemd(service: 'my-app')
```

### Multiple Services

```groovy
['api', 'worker', 'scheduler'].each { svc ->
    restartSystemd(
        service: svc,
        runAsUser: 'kontra-service'
    )
}
```

### Conditional Restart

```groovy
if (configChanged) {
    restartSystemd(service: 'my-app')
} else {
    echo "No restart needed"
}
```

## Troubleshooting

### Service Won't Start

```bash
# Check service status manually
systemctl --user status my-app

# View recent logs
journalctl --user -u my-app -n 50

# For central service user
sudo -u kontra-service systemctl --user status my-app
```

### Permission Denied

For `runAsUser`, ensure sudoers configured:

```bash
# /etc/sudoers.d/jenkins-service-deploy
jenkins ALL=(kontra-service) NOPASSWD: /bin/systemctl --user *
```

See [CENTRAL_SERVICE_USER.md](../docs/CENTRAL_SERVICE_USER.md).

### Daemon Reload Fails

```bash
# Check for syntax errors in unit files
systemd-analyze --user verify ~/.config/systemd/user/my-app.service

# Reload manually
systemctl --user daemon-reload
```

## Related

- [deploySystemd](./deploySystemd.md) - Full deployment with artifact and unit file management
- [CENTRAL_SERVICE_USER.md](../docs/CENTRAL_SERVICE_USER.md) - Central user setup
- [SYSTEMD_PERMISSIONS.md](../docs/SYSTEMD_PERMISSIONS.md) - Permission configurations
