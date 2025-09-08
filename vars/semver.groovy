// vars/semver.groovy
def call(Map cfg = [:]) {
    // Config
    final String versionFile   = (cfg.versionFile   ?: 'version.txt') as String
    final String majorToken    = (cfg.majorToken    ?: '!major') as String
    final String minorToken    = (cfg.minorToken    ?: '!minor') as String
    final String releaseToken  = (cfg.releaseToken  ?: '!release') as String
    final String strategy      = (cfg.strategy      ?: 'tag') as String     // 'tag' | 'file'
    final boolean writeFileOut = (cfg.writeFile     == false) ? false : true
    final boolean tagOnRelease = (cfg.tagOnRelease  == false) ? false : true
    final boolean pushTags     = (cfg.pushTags      == false) ? false : true
    final String tagPattern    = (cfg.tagPattern    ?: 'v[0-9]*') as String
    final String tagMode       = (cfg.tagMode       ?: 'nearest') as String // 'nearest' | 'latest'
    final boolean cumulativePatch = (cfg.cumulativePatch == true)
    final String stateFile     = (cfg.stateFile ?: '.semver-state') as String
    final String mainBranch    = (cfg.releaseBranch ?: 'main') as String
    final boolean onlyTagOnMain = (cfg.onlyTagOnMain == false) ? false : true

    // Commit metadata
    sh "git fetch --tags --force --prune || true"
    String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    env.COMMIT_MESSAGE = commitMsg
    String head = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    String branch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()

    // Determine forced bump/release intentions BEFORE potential skip logic
    String preForcedBump = (cfg.forceBump ?: '').toString().toLowerCase()
    if (!preForcedBump) {
        if (cfg.forceMajor == true || env.FORCE_MAJOR == 'true') preForcedBump = 'major'
        else if (cfg.forceMinor == true || env.FORCE_MINOR == 'true') preForcedBump = 'minor'
        else if (cfg.forcePatch == true || env.FORCE_PATCH == 'true') preForcedBump = 'patch'
    }
    boolean preForcedRelease = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
    boolean hasForcedIntent = (preForcedBump in ['major','minor','patch']) || preForcedRelease

    // Avoid bumping twice for the same commit unless a forced action is requested
    if (fileExists(stateFile)) {
        String lastSha = readFile(stateFile).trim()
        if (lastSha == head && cfg.skipOnSameCommit != false && !hasForcedIntent) {
            String reuse = fileExists(versionFile) ? readFile(versionFile).trim() : readTagVersion(tagPattern, tagMode)
            env.BUILD_VERSION = reuse ?: '0.0.0'
            return [baseVersion: env.BUILD_VERSION, version: env.BUILD_VERSION, bump: 'none',
                    isRelease: false, commitMessage: commitMsg, skipped: true, forcedIntent: false]
        }
    }

    // ---- Determine baseline (hybrid: max(tag, file))
    String baselineSource = 'tag'
    String tagVer  = (strategy == 'tag') ? readTagVersion(tagPattern, tagMode) : '0.0.0'
    String fileVer = fileExists(versionFile) ? readFile(versionFile).trim() : '0.0.0'
    String current
    if (strategy == 'file') {
        current = fileVer
        baselineSource = 'file'
    } else {
        current = maxSemver(tagVer ?: '0.0.0', fileVer ?: '0.0.0')
        baselineSource = (current == (tagVer ?: '0.0.0')) ? 'tag' : 'file'
    }

    // ---- Parse & bump
    List<String> parts = (current ?: '0.0.0').tokenize('.')
    int M = parts.size() > 0 ? parts[0] as int : 0
    int m = parts.size() > 1 ? parts[1] as int : 0
    int p = parts.size() > 2 ? parts[2] as int : 0
    int origPatch = p

    // Forced bump precedence (reuse pre-computed decision)
    String forcedBump = preForcedBump

    String bump = 'patch'
    boolean usedForcedBump = false
    switch (forcedBump) {
        case 'major': M++; m = 0; p = 0; bump = 'major'; usedForcedBump = true; break
        case 'minor': m++; p = 0; bump = 'minor'; usedForcedBump = true; break
        case 'patch': p++;         bump = 'patch'; usedForcedBump = true; break
        default:
            if (commitMsg.contains(majorToken)) { M++; m = 0; p = 0; bump = 'major' }
            else if (commitMsg.contains(minorToken)) { m++; p = 0; bump = 'minor' }
            else { p++; bump = 'patch' }
    }

    // Optional cumulative patch (only if our baseline truly came from a tag)
    int commitsSinceTag = 0
    if (cumulativePatch && baselineSource == 'tag' && bump == 'patch' && !usedForcedBump
        && !commitMsg.contains(majorToken) && !commitMsg.contains(minorToken)) {
        try {
            String lastTag = readNearestTag(tagPattern)
            if (lastTag) {
                commitsSinceTag = sh(script: "git rev-list --count ${lastTag}..HEAD", returnStdout: true).trim() as int
                if (commitsSinceTag > 0) p = origPatch + commitsSinceTag
            }
        } catch (ignored) { commitsSinceTag = 0 }
    }

    String version = "${M}.${m}.${p}"
    env.BUILD_VERSION = version

    // Persist version + state
    if (writeFileOut) writeFile file: versionFile, text: "${version}\n"
    writeFile file: stateFile, text: "${head}\n"

    // Tag only on release (and optionally only on main)
    boolean forcedRelease = preForcedRelease
    boolean isRelease = forcedRelease || commitMsg.contains(releaseToken)
    boolean canTagHere = !onlyTagOnMain || (branch == mainBranch)

    if (isRelease && tagOnRelease && canTagHere) {
        sh "git tag -a v${version} -m 'Release v${version}'"
        if (pushTags) sh "git push origin v${version}"
    }

    return [
        baseVersion     : current,
        baselineSource  : baselineSource,   // 'tag' or 'file'
        version         : version,
        bump            : bump,
        isRelease       : isRelease,
        commitMessage   : commitMsg,
        forcedBump      : usedForcedBump ? forcedBump : null,
        forcedRelease   : forcedRelease,
        commitsSinceTag : commitsSinceTag,
        cumulativePatch : cumulativePatch,
        branch          : branch
    ]
}

// ---- helpers (CPS-safe)

private String readTagVersion(String tagPattern, String tagMode) {
    String t
    if (tagMode == 'latest') {
        t = sh(script: "git -c versionsort.suffix=- tag -l '${tagPattern}' --sort=-v:refname | head -n1 || true",
               returnStdout: true).trim()
    } else {
        t = sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true",
               returnStdout: true).trim()
    }
    if (!t) return '0.0.0'
    def m = (t =~ /(\d+)\.(\d+)\.(\d+)/)
    return m.find() ? m.group(0) : '0.0.0'
}

private String readNearestTag(String tagPattern) {
    String t = sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true",
                  returnStdout: true).trim()
    return t ?: ''
}

private String maxSemver(String a, String b) {
    List<Integer> A = (a ?: '0.0.0').tokenize('.').collect { it as int }
    List<Integer> B = (b ?: '0.0.0').tokenize('.').collect { it as int }
    while (A.size() < 3) A << 0
    while (B.size() < 3) B << 0
    for (int i = 0; i < 3; i++) {
        if (A[i] != B[i]) return (A[i] > B[i]) ? a : b
    }
    return a
}
