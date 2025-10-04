// vars/restartSystemd.groovy
// Manages an existing systemd user service (daemon-reload, enable, restart, status)
def call(Map cfg = [:]) {
    if (!cfg.service) { error "restartSystemd: 'service' is required" }

    def service     = cfg.service as String
    def runAsUser   = cfg.runAsUser ?: null
    def enable      = (cfg.enable == false) ? false : true
    def reload      = (cfg.reload == false) ? false : true
    def stop        = (cfg.stop == false) ? false : true
    def start       = (cfg.start == false) ? false : true
    def showStatus  = (cfg.showStatus == false) ? false : true
    
    def runAsPrefix = runAsUser ? "sudo -u ${runAsUser} " : ''
    def ctl         = 'systemctl --user'

    echo "restartSystemd: Managing service '${service}'${runAsUser ? " as user '${runAsUser}'" : ''}"

    if (reload) {
        sh "${runAsPrefix}${ctl} daemon-reload"
        echo "restartSystemd: Reloaded systemd daemon"
    }

    if (enable) {
        sh "${runAsPrefix}${ctl} enable ${service} || true"
        echo "restartSystemd: Enabled service '${service}'"
    }

    if (stop) {
        sh "${runAsPrefix}${ctl} stop ${service} || true"
        echo "restartSystemd: Stopped service '${service}'"
    }

    if (start) {
        sh "${runAsPrefix}${ctl} start ${service}"
        echo "restartSystemd: Started service '${service}'"
    }

    if (showStatus) {
        sh "${runAsPrefix}${ctl} status --no-pager ${service} || true"
    }

    return [
        service: service,
        runAsUser: runAsUser,
        enabled: enable,
        reloaded: reload,
        stopped: stop,
        started: start
    ]
}
