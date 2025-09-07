// vars/semver.groovy
def call(Map cfg = [:]) {
    // Config
    def versionFile   = cfg.versionFile   ?: 'version.txt'
    def majorToken    = cfg.majorToken    ?: '!major'
    def minorToken    = cfg.minorToken    ?: '!minor'
    def releaseToken  = cfg.releaseToken  ?: '!release'
    def strategy      = cfg.strategy      ?: 'tag'   // 'tag' | 'file'
    def writeFileOut  = (cfg.writeFile    == false) ? false : true
    def tagOnRelease  = (cfg.tagOnRelease == false) ? false : true
    def pushTags      = (cfg.pushTags     == false) ? false : true

    // Make sure we have tags for 'tag' strategy
    if (strategy == 'tag') {
        sh(label: 'Fetch tags (ignore if shallow)',
           script: 'git fetch --tags --force || true')
    }

    // Last commit message (used for bump & release)
    def commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    env.COMMIT_MESSAGE = commitMsg

    // Determine current version
    String current = '0.0.0'
    if (strategy == 'tag') {
        def tag = sh(script: "git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || echo v0.0.0",
                     returnStdout: true).trim()
        current = tag.startsWith('v') ? tag.substring(1) : tag
    } else {
        if (fileExists(versionFile)) {
            current = readFile(versionFile).trim()
        } else {
            writeFile file: versionFile, text: current
        }
    }

    // Parse & bump
    def parts = current.tokenize('.')
    int M = (parts.size() > 0 ? parts[0] as int : 0)
    int m = (parts.size() > 1 ? parts[1] as int : 0)
    int p = (parts.size() > 2 ? parts[2] as int : 0)

    String bump = 'patch'
    if (commitMsg.contains(majorToken)) { M++; m = 0; p = 0; bump = 'major' }
    else if (commitMsg.contains(minorToken)) { m++; p = 0; bump = 'minor' }
    else { p++; bump = 'patch' }

    String version = "${M}.${m}.${p}"
    env.BUILD_VERSION = version

    // Persist to file if desired
    if (writeFileOut) {
        writeFile file: versionFile, text: version
    }

    // Tag on release
    boolean isRelease = commitMsg.contains(releaseToken)
    if (isRelease && tagOnRelease) {
        sh "git tag -a v${version} -m 'Release v${version}'"
        if (pushTags) {
            sh "git push origin v${version}"
        }
    }

    return [
        baseVersion: current,
        version: version,
        bump: bump,
        isRelease: isRelease,
        commitMessage: commitMsg
    ]
}
