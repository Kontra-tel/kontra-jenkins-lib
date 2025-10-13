// vars/deploySystemd.groovy
// Deploy services as systemd user units (user units only - no system-wide services)
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
    // Optional: Run systemd commands as a different user (e.g., 'kontra-service')
    // This allows a central service user to own all deployed services
    def runAsUser    = cfg.runAsUser  ?: null
    def restart      = cfg.restart    ?: 'always' // on-failure|always
    def restartSec   = cfg.restartSec ?: '3'
    def installUnit  = (cfg.installUnit == false) ? false : true
    def overwriteUnit = (cfg.overwriteUnit == false) ? false : true
    
    // User units only - simplified configuration
    def homeDir      = runAsUser ? "/home/${runAsUser}" : env.HOME
    def unitPath     = cfg.unitPath ?: "${homeDir}/.config/systemd/user/${service}.service"
    // Never use sudo; when runAsUser is specified, we only derive paths (e.g., homeDir) but do not switch user

    // Optionally deploy latest artifact
    String deployedJar = null
    if (artifactGlob) {
        def latest = sh(script: "ls -t ${artifactGlob} 2>/dev/null | head -n 1", returnStdout: true).trim()
        if (!latest) { error "deploySystemd: no artifacts matched '${artifactGlob}'" }
        if (!dryRun) {
            sh "mkdir -p '${targetDir}'"
            sh "install -m 644 '${latest}' '${targetDir}/${targetName}'"
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
            sh "mkdir -p '${workingDir}'"
            sh "install -m 755 '${repoLaunchScript}' '${launchScriptPath}'"
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
            sh "mkdir -p '${workingDir}'"
            def tmp = ".tmp.${service}.launch.sh"
            writeFile file: tmp, text: script
            sh "install -m 755 '${tmp}' '${launchScriptPath}'"
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
                WorkingDirectory=${workingDir}
                ${envFile ? "EnvironmentFile=-${envFile}" : ''}
                ExecStart=${execStart}
                Restart=${restart}
                RestartSec=${restartSec}
                StandardOutput=journal
                StandardError=journal
                SyslogIdentifier=${service}

                [Install]
                WantedBy=default.target
            """.stripIndent()
            if (dryRun) {
                unitContent = unit
                echo "deploySystemd(dryRun): would write unit -> ${unitPath} (no file created)"
            } else {
                def tmp = ".tmp.${service}.service"
                writeFile file: tmp, text: unit
                // Create user unit without sudo; assumes current user has required permissions
                sh "mkdir -p '${homeDir}/.config/systemd/user'"
                sh "install -m 644 '${tmp}' '${unitPath}'"
                sh "rm -f '${tmp}'"
                echo "deploySystemd: wrote unit -> ${unitPath}"
                unitContent = unit
            }
            wroteUnit = true
        } else {
            echo "deploySystemd: unit exists at ${unitPath} (overwriteUnit=false) — leaving as-is"
        }
    }

    // Ensure working dir perms are sane
    if (!dryRun) {
        sh "mkdir -p '${workingDir}'"
    }

    // Use the new restartSystemd module for service management
    def restartResult = null
    if (!dryRun) {
        restartResult = restartSystemd(
            service: service,
            runAsUser: runAsUser,
            enable: (cfg.enable == false) ? false : true
        )
    } else {
        echo "deploySystemd(dryRun): would call restartSystemd to reload+enable+restart ${service}"
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
        restarted    : !dryRun,
        restartResult: restartResult
    ]
}
