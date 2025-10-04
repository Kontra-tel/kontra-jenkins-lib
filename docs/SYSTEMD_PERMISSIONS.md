# Systemd Permissions Guide

This guide explains how to configure systemd permissions for Jenkins deployments without requiring full sudo access.

## Three Approaches

### 1. User Units (Recommended - No Setup Required)

**Best for:** Most Jenkins deployments, development, testing

**Pros:**
- No special permissions needed
- No sudo required
- User isolation
- Simplest setup

**Cons:**
- Services stop on user logout (unless lingering enabled)
- Limited to user's home directory

**Setup:**
```groovy
deploySystemd(
  service: 'my-app',
  workingDir: '/home/jenkins/my-app'
  // useUserUnit: true (default)
  // useSudo: false (default)
)
```

**Service location:** `~/.config/systemd/user/my-app.service`

**Enable 24/7 operation:**
```bash
sudo loginctl enable-linger jenkins
```

---

### 2. Polkit Rules (Recommended for System Units)

**Best for:** System-wide services, production deployments

**Pros:**
- Fine-grained control
- Can restrict to specific services
- Audit trail via Polkit
- No password needed

**Cons:**
- Requires one-time root setup
- Distribution-specific syntax

**Setup:**

Create `/etc/polkit-1/rules.d/10-jenkins-systemd.rules`:

```javascript
// Allow jenkins user to manage services
polkit.addRule(function(action, subject) {
    if (action.id == "org.freedesktop.systemd1.manage-units" &&
        subject.user == "jenkins") {
        return polkit.Result.YES;
    }
});

// Or restrict to specific services only
polkit.addRule(function(action, subject) {
    var allowedServices = [
        "my-app.service",
        "api.service",
        "worker.service"
    ];
    
    if (action.id == "org.freedesktop.systemd1.manage-units" &&
        subject.user == "jenkins" &&
        allowedServices.indexOf(action.lookup("unit")) >= 0) {
        return polkit.Result.YES;
    }
});

// Reload Polkit after changes
polkit.addRule(function(action, subject) {
    if (action.id == "org.freedesktop.systemd1.reload-daemon" &&
        subject.user == "jenkins") {
        return polkit.Result.YES;
    }
});
```

**Reload Polkit:**
```bash
sudo systemctl restart polkit
```

**Usage in Jenkins:**
```groovy
deploySystemd(
  service: 'my-app',
  workingDir: '/opt/my-app',
  useUserUnit: false,  // System unit
  useSudo: false       // No sudo (Polkit allows it)
)
```

**Service location:** `/etc/systemd/system/my-app.service`

---

### 3. Sudoers with NOPASSWD

**Best for:** Quick setup, CI/CD pipelines

**Pros:**
- Simple to configure
- Can restrict to specific commands
- Well-documented

**Cons:**
- Less granular than Polkit
- Must list each command
- Harder to audit

**Setup:**

Create `/etc/sudoers.d/jenkins` (use `visudo -f`):

```bash
# Allow jenkins to manage specific services without password
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl start my-app.service
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl stop my-app.service
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl restart my-app.service
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl status my-app.service
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl daemon-reload
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl enable my-app.service
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl disable my-app.service

# Or allow all systemctl commands (less secure)
jenkins ALL=(ALL) NOPASSWD: /bin/systemctl *

# File operations for unit installation
jenkins ALL=(ALL) NOPASSWD: /usr/bin/install -m 644 * /etc/systemd/system/*.service
jenkins ALL=(ALL) NOPASSWD: /usr/bin/mkdir -p /opt/*
jenkins ALL=(ALL) NOPASSWD: /usr/bin/chown -R jenkins\:jenkins /opt/*
```

**Set correct permissions:**
```bash
sudo chmod 440 /etc/sudoers.d/jenkins
```

**Usage in Jenkins:**
```groovy
deploySystemd(
  service: 'my-app',
  workingDir: '/opt/my-app',
  useUserUnit: false,  // System unit
  useSudo: true        // Use sudo (but no password prompt)
)
```

**Service location:** `/etc/systemd/system/my-app.service`

---

## Comparison Matrix

| Approach | Setup Complexity | Security | Flexibility | Best For |
|----------|-----------------|----------|-------------|----------|
| **User Units** | None | Highest | Medium | Dev, testing, simple apps |
| **Polkit** | Medium | High | Highest | Production, fine-grained control |
| **Sudoers NOPASSWD** | Low | Medium | Low | Quick CI/CD, legacy systems |

---

## Testing Your Setup

### Test Polkit/Sudoers Configuration

```bash
# As jenkins user, try without sudo:
systemctl start my-app
systemctl status my-app
systemctl stop my-app

# Should work without password prompt
```

### Test User Units

```bash
# As jenkins user:
systemctl --user start my-app
systemctl --user status my-app

# Check if lingering is enabled:
loginctl show-user jenkins | grep Linger
```

---

## Troubleshooting

### "Failed to start: Permission denied"

**User Units:**
```bash
# Check unit file permissions
ls -la ~/.config/systemd/user/

# Check if user systemd is running
systemctl --user status
```

**System Units:**
```bash
# Check Polkit rules
pkaction --action-id org.freedesktop.systemd1.manage-units --verbose

# Check sudoers
sudo -l -U jenkins

# View Polkit logs
journalctl -u polkit
```

### Services Stop on Logout

```bash
# Enable lingering for jenkins user
sudo loginctl enable-linger jenkins

# Verify
loginctl show-user jenkins | grep Linger
# Should show: Linger=yes
```

### Polkit Not Working

```bash
# Restart Polkit
sudo systemctl restart polkit

# Check Polkit rules syntax
pkaction --action-id org.freedesktop.systemd1.manage-units

# View Polkit logs
journalctl -xe -u polkit
```

---

## Security Best Practices

1. **Prefer User Units** - Use when possible for better isolation
2. **Polkit over Sudoers** - More granular and auditable
3. **Limit Scope** - Only grant permissions for specific services
4. **Regular Audits** - Review permissions periodically
5. **Logging** - Enable audit logging for systemctl commands
6. **Service User** - Run services as dedicated non-root users

---

## Example Polkit Rules by Use Case

### Development Environment
```javascript
// Allow all developers to manage dev services
polkit.addRule(function(action, subject) {
    if (action.id == "org.freedesktop.systemd1.manage-units" &&
        subject.isInGroup("developers")) {
        return polkit.Result.YES;
    }
});
```

### Production Environment
```javascript
// Strict: Only specific services, only specific user
polkit.addRule(function(action, subject) {
    var prodServices = ["api.service", "worker.service"];
    
    if (action.id == "org.freedesktop.systemd1.manage-units" &&
        subject.user == "jenkins" &&
        prodServices.indexOf(action.lookup("unit")) >= 0 &&
        (action.lookup("verb") == "start" ||
         action.lookup("verb") == "stop" ||
         action.lookup("verb") == "restart")) {
        return polkit.Result.YES;
    }
});
```

### CI/CD Pipeline
```javascript
// Allow reload-daemon for deployments
polkit.addRule(function(action, subject) {
    if ((action.id == "org.freedesktop.systemd1.manage-units" ||
         action.id == "org.freedesktop.systemd1.reload-daemon") &&
        subject.user == "jenkins") {
        return polkit.Result.YES;
    }
});
```

---

## References

- [systemd User Units](https://wiki.archlinux.org/title/Systemd/User)
- [Polkit Documentation](https://www.freedesktop.org/software/polkit/docs/latest/)
- [Sudoers Manual](https://www.sudo.ws/docs/man/sudoers.man/)
- [loginctl enable-linger](https://www.freedesktop.org/software/systemd/man/loginctl.html)
