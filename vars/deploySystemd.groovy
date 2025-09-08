// vars/deploySystemd.groovy
def call(Map cfg = [:]) {
    if (!cfg.service) { error "deploySystemd: 'service' is required" }

    def dryRun       = (cfg.dryRun == true)
    def service      = cfg.service as String
    def desc         = cfg.description ?: "Service ${service}"
    def workingDir   = cfg.workingDir ?: "/opt/${service}"
    def envFile      = cfg.envFile    ?: null
    def execStartCfg = cfg.execStart  ?: null     // if provided, used as-is
    def jarPath      = cfg.jarPath    ?: null     // OR
    def artifactGlob = cfg.artifactGlob ?: null   // copy latest build artifact
    def targetDir    = cfg.targetDir  ?: workingDir
    def targetName   = cfg.targetName ?: "${service}.jar"
    def javaBin      = cfg.javaBin    ?: '/usr/bin/java'
    def javaOpts     = cfg.javaOpts   ?: (env.JAVA_OPTS ?: '')
    def appArgs      = cfg.appArgs    ?: ''
    def user         = cfg.user       ?: 'root'
    def group        = cfg.group      ?: user
    def restart      = cfg.restart    ?: 'always' // on-failure|always
    def restartSec   = cfg.restartSec ?: '3'
    def enable       = (cfg.enable == false) ? false : true
    def installUnit  = (cfg.installUnit == false) ? false : true
    def overwriteUnit = (cfg.overwriteUnit == false) ? false : true
    def useUserUnit  = (cfg.useUserUnit == true)
    def unitPath     = cfg.unitPath ?: (useUserUnit ? "${env.HOME}/.config/systemd/user/${service}.service"
                                                    : "/etc/systemd/system/${service}.service")
    def useSudo      = (cfg.useSudo == false || useUserUnit) ? false : true
    def sudo         = useSudo ? 'sudo ' : ''
    def ctl          = useUserUnit ? 'systemctl --user' : 'systemctl'

    // Minimal shell escaper

    // Optionally deploy latest artifact
    String deployedJar = null
    if (artifactGlob) {
        def latest = sh(script: "ls -t ${artifactGlob} 2>/dev/null | head -n 1", returnStdout: true).trim()
        if (!latest) { error "deploySystemd: no artifacts matched '${artifactGlob}'" }
        if (!dryRun) {
            sh "${sudo}mkdir -p '${targetDir}'"
            sh "${sudo}install -m 644 '${latest}' '${targetDir}/${targetName}'"
        }
        deployedJar = "${targetDir}/${targetName}"
        echo "deploySystemd: ${dryRun ? 'would deploy' : 'deployed'} artifact -> ${deployedJar} (from ${latest})"
    }

    // compute ExecStart (may depend on deployedJar)
    def execStart = execStartCfg
    if (!execStart) {
        def jarToRun = deployedJar ?: jarPath ?: "${targetDir}/${targetName}"
        List<String> parts = []
        parts << javaBin
        if (javaOpts?.trim()) { parts << javaOpts.trim() }
        parts << '-jar'
        parts << jarToRun
        if (appArgs?.trim()) { parts << appArgs.trim() }
        execStart = parts.join(' ')
    }

    // Optionally (re)install the unit
    boolean wroteUnit = false
    String unitContent = null
    if (installUnit) {
        // Only check existence if not dryRun (since file may not actually exist)
        def exists = false
        if (!dryRun) {
            exists = sh(script: "[ -f '${unitPath}' ] && echo yes || true", returnStdout: true).trim() == 'yes'
        }
        if (!exists || overwriteUnit) {
            def unit = """\
                [Unit]
                Description=${desc}
                After=network.target

                [Service]
                Type=simple
                User=${user}
                Group=${group}
                WorkingDirectory=${workingDir}
                ${envFile ? "EnvironmentFile=-${envFile}" : ''}
                ExecStart=${execStart}
                Restart=${restart}
                RestartSec=${restartSec}
                StandardOutput=journal
                StandardError=journal
                SyslogIdentifier=${service}

                [Install]
                WantedBy=multi-user.target
            """.stripIndent()
            if (dryRun) {
                unitContent = unit
                echo "deploySystemd(dryRun): would write unit -> ${unitPath} (no file created)"
            } else {
                def tmp = ".tmp.${service}.service"
                writeFile file: tmp, text: unit
                if (!useUserUnit) {
                    sh "${sudo}install -m 644 '${tmp}' '${unitPath}'"
                } else {
                    sh "mkdir -p '${env.HOME}/.config/systemd/user'"
                    sh "install -m 644 '${tmp}' '${unitPath}'"
                }
                sh "rm -f '${tmp}'"
                echo "deploySystemd: wrote unit -> ${unitPath}"
                unitContent = unit
            }
            wroteUnit = true
        } else {
            echo "deploySystemd: unit exists at ${unitPath} (overwriteUnit=false) — leaving as-is"
        }
    }

    // Ensure working dir + env perms are sane
    if (!dryRun) {
        sh "${sudo}mkdir -p '${workingDir}'"
        if (user && !useUserUnit) {
            sh "${sudo}chown -R ${user}:${group} '${workingDir}' || true"
        }
    }

    // Restart service
    if (!dryRun) {
        sh "${sudo}${ctl} daemon-reload"
        if (enable) { sh "${sudo}${ctl} enable ${service} || true" }
        sh "${sudo}${ctl} stop ${service} || true" // stop first
        sh "${sudo}${ctl} start ${service}"
        sh "${sudo}${ctl} status --no-pager ${service} || true"
    } else {
        echo "deploySystemd(dryRun): would reload+enable+restart ${service}"
    }

    return [
        dryRun       : dryRun,
        service      : service,
        unitPath     : unitPath,
        unitWritten  : wroteUnit,
        unitContent  : unitContent,
        deployedJar  : deployedJar,
        execStart    : execStart,
        restarted    : !dryRun
    ]
}
