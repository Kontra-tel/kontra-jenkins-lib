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
    def tagPattern = cfg.tagPattern ?: 'v[0-9]*'     // e.g. 'v[0-9]*' or 'api-v[0-9]*'
    def tagMode    = (cfg.tagMode ?: 'nearest')      // 'nearest' or 'latest'

    // Make sure we have tags for 'tag' strategy
    if (strategy == 'tag') {
        sh(label: 'Fetch tags (ignore if shallow)',
           script: 'git fetch --tags --force || true')
    }

    // Last commit message (used for bump & release)
    def commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    env.COMMIT_MESSAGE = commitMsg

    // Ensure we can see remote tags (ok with shallow clones)
    sh(label: 'Fetch tags', script: "git fetch --tags --force --prune 2>/dev/null || true")

    // Determine current version
    String current = '0.0.0'
    if (strategy == 'tag') {
        String foundTag = ''
        if (tagMode == 'latest') {
            // Latest version-looking tag in the repo (version-aware sort)
            foundTag = sh(
            script: "git -c versionsort.suffix=- tag -l '${tagPattern}' --sort=-v:refname | head -n1",
            returnStdout: true
            ).trim()
        } else {
            // Nearest tag reachable from HEAD (what you had)
            foundTag = sh(
            script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true",
            returnStdout: true
            ).trim()
        }

        if (!foundTag) foundTag = 'v0.0.0'  // no tags yet

        // Normalize: grab first X.Y.Z, ignore prefix/suffix like 'v' or '-rc.1'
        def m = (foundTag =~ /(\d+)\.(\d+)\.(\d+)/)
        current = m.find() ? m.group(0) : '0.0.0'
    } else {
        if (fileExists(versionFile)) {
            current = readFile(versionFile).trim()
        } else {
            writeFile file: versionFile, text: current + '\n'
        }
    }

    // Parse & bump
    def parts = current.tokenize('.')
    int M = (parts.size() > 0 ? parts[0] as int : 0)
    int m = (parts.size() > 1 ? parts[1] as int : 0)
    int p = (parts.size() > 2 ? parts[2] as int : 0)
    int origPatch = p

    // Forced bump precedence (explicit cfg > env flags > commit message tokens)
    String forcedBump = (cfg.forceBump ?: '')?.toString().toLowerCase()
    if (!forcedBump) {
        if (cfg.forceMajor == true || env.FORCE_MAJOR == 'true') forcedBump = 'major'
        else if (cfg.forceMinor == true || env.FORCE_MINOR == 'true') forcedBump = 'minor'
        else if (cfg.forcePatch == true || env.FORCE_PATCH == 'true') forcedBump = 'patch'
    }

    String bump = 'patch'
    boolean usedForcedBump = false
    switch (forcedBump) {
        case 'major':
            M++; m = 0; p = 0; bump = 'major'; usedForcedBump = true; break
        case 'minor':
            m++; p = 0; bump = 'minor'; usedForcedBump = true; break
        case 'patch':
            p++; bump = 'patch'; usedForcedBump = true; break
        default:
            if (commitMsg.contains(majorToken)) { M++; m = 0; p = 0; bump = 'major' }
            else if (commitMsg.contains(minorToken)) { m++; p = 0; bump = 'minor' }
            else { p++; bump = 'patch' }
    }

    // Optional cumulative patch bump: increment patch by number of commits since last tag instead of just +1
    int commitsSinceTag = 0
    boolean cumulativePatch = (cfg.cumulativePatch == true)
    if (cumulativePatch && strategy == 'tag' && bump == 'patch' && !usedForcedBump && !commitMsg.contains(majorToken) && !commitMsg.contains(minorToken)) {
        try {
            commitsSinceTag = sh(script: "git rev-list --count $(git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || echo v0.0.0)..HEAD", returnStdout: true).trim() as int
        } catch (ignored) { commitsSinceTag = 0 }
        if (commitsSinceTag > 0) {
            p = origPatch + commitsSinceTag
        }
    }

    String version = "${M}.${m}.${p}"
    env.BUILD_VERSION = version

    // Persist to file if desired
    if (writeFileOut) {
        writeFile file: versionFile, text: version
    }

    // Tag on release
    boolean forcedRelease = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
    boolean isRelease = forcedRelease || commitMsg.contains(releaseToken)
    // If release forced but no explicit bump token or forced bump changed version beyond current? ensure at least patch increments
    if (isRelease && !usedForcedBump && !commitMsg.contains(majorToken) && !commitMsg.contains(minorToken) && bump == 'patch') {
        // Already incremented patch in normal path; nothing else needed.
        // (If future logic skips patch increment, enforce here.)
    }
    if (isRelease && tagOnRelease) {
        sh "git tag -a v${version} -m 'Release v${version}'"
        if (pushTags) {
            sh "git push origin v${version}"
        }
    }

    return [
        baseVersion   : current,
        version       : version,
        bump          : bump,
        isRelease     : isRelease,
        commitMessage : commitMsg,
        forcedBump    : usedForcedBump ? forcedBump : null,
    forcedRelease : forcedRelease,
    commitsSinceTag: commitsSinceTag,
    cumulativePatch: cumulativePatch
    ]
}
