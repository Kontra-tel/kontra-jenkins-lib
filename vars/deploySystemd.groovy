// vars/deploySystemd.groovy
def call(Map cfg = [:]) {
    if (!cfg.service) { error "deploySystemd: 'service' is required" }

    def dryRun       = (cfg.dryRun == true)
    def service      = cfg.service as String
    def desc         = cfg.description ?: "Service ${service}"
    def workingDir   = cfg.workingDir ?: "/opt/${service}"
    def envFile      = cfg.envFile    ?: null
    // If a repo-provided launch script exists, we will stage it and use it by default
    def repoLaunchScript = cfg.repoLaunchScript ?: 'launch.sh'
    // Optional: high-level start command → will be written to a launch script and used for ExecStart
    def startCommand = cfg.startCommand ?: null
    def launchScriptName = cfg.launchScriptName ?: 'launch.sh'
    def launchScriptPath = cfg.launchScriptPath ?: "${workingDir}/${launchScriptName}"
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

    // Optionally stage a repo launch.sh into workingDir (generic projects)
    String launchScriptWrittenPath = null
    String launchScriptContent = null
    if (!startCommand && fileExists(repoLaunchScript)) {
        if (dryRun) {
            launchScriptWrittenPath = launchScriptPath
            try { launchScriptContent = readFile(file: repoLaunchScript) } catch (Throwable ignore) {}
            echo "deploySystemd(dryRun): would install repo launch script '${repoLaunchScript}' -> ${launchScriptPath}"
        } else {
            sh "${sudo}mkdir -p '${workingDir}'"
            if (useSudo) {
                sh "${sudo}install -m 755 '${repoLaunchScript}' '${launchScriptPath}'"
            } else {
                sh "install -m 755 '${repoLaunchScript}' '${launchScriptPath}'"
            }
            launchScriptWrittenPath = launchScriptPath
            echo "deploySystemd: installed repo launch script -> ${launchScriptWrittenPath}"
        }
    }

    // Optionally create a launch script from startCommand
    // (this takes precedence over a repo launch.sh if both are provided)
    if (startCommand) {
        String script = """#!/usr/bin/env bash
set -euo pipefail
${startCommand}
"""
        if (dryRun) {
            launchScriptWrittenPath = launchScriptPath
            launchScriptContent = script
            echo "deploySystemd(dryRun): would write launch script -> ${launchScriptPath}"
        } else {
            // ensure working directory exists before installing the script
            sh "${sudo}mkdir -p '${workingDir}'"
            def tmp = ".tmp.${service}.launch.sh"
            writeFile file: tmp, text: script
            if (useSudo) {
                sh "${sudo}install -m 755 '${tmp}' '${launchScriptPath}'"
            } else {
                sh "install -m 755 '${tmp}' '${launchScriptPath}'"
            }
            sh "rm -f '${tmp}'"
            launchScriptWrittenPath = launchScriptPath
            echo "deploySystemd: wrote launch script -> ${launchScriptWrittenPath}"
        }
    }

    // compute ExecStart (may depend on deployedJar)
    def execStart = execStartCfg
    if (!execStart) {
    if (startCommand || launchScriptWrittenPath) {
            // Run the generated launch script through bash -lc to allow shell features
            execStart = "/usr/bin/env bash -lc '${launchScriptPath}'"
        } else {
            def jarToRun = deployedJar ?: jarPath ?: "${targetDir}/${targetName}"
            List<String> parts = []
            parts << javaBin
            if (javaOpts?.trim()) { parts << javaOpts.trim() }
            parts << '-jar'
            parts << jarToRun
            if (appArgs?.trim()) { parts << appArgs.trim() }
            execStart = parts.join(' ')
        }
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
        launchScript : launchScriptWrittenPath,
        launchScriptContent: launchScriptContent,
        restarted    : !dryRun
    ]
}
