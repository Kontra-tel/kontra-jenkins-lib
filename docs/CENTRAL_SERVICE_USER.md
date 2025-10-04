# Using a Central Service User for Systemd Deployments

This guide shows how to set up a dedicated service user (e.g., `kontra-service`) that owns all deployed services, providing better isolation and security.

## Why Use a Central Service User?

- **Isolation**: All services run under a single, dedicated non-privileged account
- **Security**: Services don't run as root or under individual developer accounts
- **Consistency**: Easier permission management across multiple services
- **Auditing**: All service activities attributed to one account

## Setup Process

### 1. Create the Service User

On your deployment target machine:

```bash
# Create a system user for running services
sudo useradd -r -m -s /bin/bash kontra-service
# -r = system account (UID < 1000)
# -m = create home directory
# -s = login shell (needed for user units)

# Optional: Set a password if you need to login as this user
sudo passwd kontra-service

# Enable lingering so user services run 24/7 (even when not logged in)
sudo loginctl enable-linger kontra-service

# Verify lingering is enabled
loginctl show-user kontra-service | grep Linger
# Should show: Linger=yes
```

### 2. Grant Jenkins Permission to Deploy

You need to allow the `jenkins` user to run commands as `kontra-service`. Choose one approach:

#### Option A: Sudoers Configuration (Recommended)

Create `/etc/sudoers.d/jenkins-service-deploy`:

```bash
# Allow jenkins to run systemctl as kontra-service
jenkins ALL=(kontra-service) NOPASSWD: /bin/systemctl --user *
jenkins ALL=(kontra-service) NOPASSWD: /usr/bin/loginctl --user *

# Allow jenkins to manage files in service user's home directory
jenkins ALL=(kontra-service) NOPASSWD: /usr/bin/install -m * * /home/kontra-service/*
jenkins ALL=(kontra-service) NOPASSWD: /bin/mkdir -p /home/kontra-service/*
jenkins ALL=(kontra-service) NOPASSWD: /bin/cp * /home/kontra-service/*
jenkins ALL=(kontra-service) NOPASSWD: /bin/rm -f /home/kontra-service/*
```

Set correct permissions:

```bash
sudo chmod 440 /etc/sudoers.d/jenkins-service-deploy
sudo visudo -c  # Verify syntax
```

#### Option B: Group-Based Access

```bash
# Create a deployment group
sudo groupadd deploy-group

# Add both users to the group
sudo usermod -a -G deploy-group jenkins
sudo usermod -a -G deploy-group kontra-service

# Set up shared directory access
sudo chown kontra-service:deploy-group /home/kontra-service
sudo chmod 775 /home/kontra-service

# Allow group write access to systemd user directory
sudo -u kontra-service mkdir -p /home/kontra-service/.config/systemd/user
sudo chown -R kontra-service:deploy-group /home/kontra-service/.config
sudo chmod -R 775 /home/kontra-service/.config
```

**Note**: This approach is less secure as it grants broader access.

### 3. Update Your Pipeline

Use the `runAsUser` parameter in your Jenkinsfile:

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Deploy') {
            steps {
                script {
                    // Deploy using the central service user
                    deploySystemd(
                        service: 'my-app',
                        runAsUser: 'kontra-service',
                        useUserUnit: true,  // Uses user units under kontra-service
                        artifactGlob: 'build/libs/*.jar',
                        workingDir: '/home/kontra-service/apps/my-app',
                        envFile: '/home/kontra-service/apps/my-app/.env',
                        startCommand: 'java -jar my-app.jar'
                    )
                }
            }
        }
    }
}
```

## How It Works

When you specify `runAsUser: 'kontra-service'`:

1. **File Operations**: Files are created as jenkins user initially (temporary)
2. **Installation**: The `install` command copies files with proper ownership
3. **Systemd Commands**: All `systemctl --user` commands run as `kontra-service` via `sudo -u`
4. **Service Execution**: The service itself runs under `kontra-service` (as specified in the unit file's `User=` field)

### Example Commands Generated

Without `runAsUser`:
```bash
systemctl --user daemon-reload
systemctl --user enable my-app
systemctl --user start my-app
```

With `runAsUser: 'kontra-service'`:
```bash
sudo -u kontra-service systemctl --user daemon-reload
sudo -u kontra-service systemctl --user enable my-app
sudo -u kontra-service systemctl --user start my-app
```

## Directory Structure

For a service named `my-app` running as `kontra-service`:

```
/home/kontra-service/
├── .config/
│   └── systemd/
│       └── user/
│           └── my-app.service          # Unit file
├── apps/
│   └── my-app/
│       ├── my-app.jar                   # Application
│       ├── .env                         # Environment variables
│       └── launch.sh                    # Optional launch script
└── .local/
    └── share/
        └── systemd/
            └── ...                      # Systemd state files
```

## Managing Services

### As Jenkins (during deployment)

```bash
# The pipeline handles this automatically, but you can also:
sudo -u kontra-service systemctl --user status my-app
sudo -u kontra-service systemctl --user restart my-app
sudo -u kontra-service systemctl --user stop my-app
```

### As Root (for maintenance)

```bash
# View service status
sudo -u kontra-service systemctl --user status my-app

# View logs
sudo journalctl -u my-app --user-unit -f

# List all services
sudo -u kontra-service systemctl --user list-units
```

### As Service User (manual operations)

```bash
# Switch to service user
sudo -u kontra-service -i

# Now you're logged in as kontra-service
systemctl --user status my-app
systemctl --user restart my-app
journalctl --user -u my-app -f
```

## Troubleshooting

### Permission Denied Errors

```bash
# Verify sudoers configuration
sudo visudo -c
cat /etc/sudoers.d/jenkins-service-deploy

# Test sudo access as jenkins
sudo -u jenkins sudo -u kontra-service systemctl --user status

# Check file ownership
ls -la /home/kontra-service/.config/systemd/user/
```

### Service Won't Start After Reboot

```bash
# Verify lingering is enabled
loginctl show-user kontra-service | grep Linger

# If not enabled:
sudo loginctl enable-linger kontra-service
```

### File Ownership Issues

```bash
# Fix ownership of service directories
sudo chown -R kontra-service:kontra-service /home/kontra-service/apps

# Fix systemd directory permissions
sudo -u kontra-service mkdir -p ~/.config/systemd/user
sudo chown -R kontra-service:kontra-service /home/kontra-service/.config
```

### Can't See Logs

```bash
# Logs for user services are in the user journal
sudo journalctl --user -u my-app -f

# Or switch to the service user
sudo -u kontra-service -i
journalctl --user -u my-app -f
```

## Security Considerations

1. **Principle of Least Privilege**: Only grant jenkins the minimum sudo permissions needed
2. **Path Restrictions**: Use absolute paths in sudoers to prevent command substitution
3. **Regular Auditing**: Review `sudo -l -U jenkins` periodically
4. **File Permissions**: Keep service directories owned by `kontra-service:kontra-service`
5. **SELinux/AppArmor**: May require additional configuration for user services

## Multiple Environment Setup

For dev/staging/prod environments, you can use different service users:

```groovy
def serviceUser = [
    'dev': 'kontra-service-dev',
    'staging': 'kontra-service-staging', 
    'prod': 'kontra-service'
][env.DEPLOY_ENV]

deploySystemd(
    service: 'my-app',
    runAsUser: serviceUser,
    // ... other parameters
)
```

## References

- [Systemd User Units](https://wiki.archlinux.org/title/Systemd/User)
- [Sudoers Manual](https://www.sudo.ws/docs/man/1.8.15/sudoers.man/)
- [loginctl Man Page](https://www.freedesktop.org/software/systemd/man/loginctl.html)
- [SYSTEMD_PERMISSIONS.md](./SYSTEMD_PERMISSIONS.md) - Alternative permission approaches
