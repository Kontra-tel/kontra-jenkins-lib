// vars/semver.groovy
//
// Semantic versioning for Jenkins Pipelines with optional Git tags and GitHub Releases.
//
// Highlights
// - Two baselines:
//     * strategy:'file'  → baseline from version.txt
//     * strategy:'tag'   → baseline from tags (or hybrid with version.txt unless strictTagBaseline:true)
// - Bump on each build using tokens in the last commit message: !major / !minor / (else patch)
//   Force via params/env: forceBump:'major'|'minor'|'patch' or FORCE_MAJOR/FORCE_MINOR/FORCE_PATCH
// - Optional cumulativePatch: patch += commits since last tag (when baseline came from a tag)
// - Prevents double-bumps on the same commit via a small state file
// - Tagging modes:
//     * default: tag only when !release (or forceRelease)  → tagOnRelease:true
//     * alwaysTag:true  → tag EVERY version bump
// - Can push tags using GitHub App or PAT credentials and optionally create a GitHub Release
//
// Example:
//   def v = semver(
//     strategy: 'tag',                 // or 'file'
//     strictTagBaseline: false,        // true = ignore version.txt for baseline
//     alwaysTag: false,                // true = tag every bump
//     tagOnRelease: true,              // tag when commit contains !release
//     pushTags: true,
//     credentialsId: 'Jenkins-Kontra', // GitHub App or PAT
//     owner: 'Kontra-tel',             // helps App token inference
//     createGithubRelease: true,
//     onlyTagOnMain: true,             // only tag on main by default
//     releaseBranch: 'main'
//   )
//   echo "Version: ${v.version}  source=${v.baselineSource}  tagged=${v.tagPushed}  released=${v.githubReleased}"
//
def call(Map cfg = [:]) {
    // -------- Config --------
    final String  versionFile        = (cfg.versionFile ?: 'version.txt') as String
    final String  majorToken         = (cfg.majorToken ?: '!major') as String
    final String  minorToken         = (cfg.minorToken ?: '!minor') as String
    final String  releaseToken       = (cfg.releaseToken ?: '!release') as String
    final String  strategy           = (cfg.strategy ?: 'tag') as String        // 'tag' | 'file'
    final boolean writeFileOut       = (cfg.writeFile == false) ? false : true
    final boolean tagOnRelease       = (cfg.tagOnRelease == false) ? false : true
    final boolean pushTags           = (cfg.pushTags == false) ? false : true
    final String  tagPattern         = (cfg.tagPattern ?: 'v[0-9]*') as String
    final String  tagMode            = (cfg.tagMode ?: 'nearest') as String     // 'nearest' | 'latest'
    final boolean cumulativePatch    = (cfg.cumulativePatch == true)
    final String  stateFile          = (cfg.stateFile ?: '.semver-state') as String
    final String  mainBranch         = (cfg.releaseBranch ?: 'main') as String
    final boolean onlyTagOnMain      = (cfg.onlyTagOnMain == false) ? false : true
    final boolean alwaysTag          = (cfg.alwaysTag == true)                   // tag every bump
    final boolean strictTagBaseline  = (cfg.strictTagBaseline == true)           // baseline only from tags

    // GitHub integration (optional)
    final String  credentialsId      = (cfg.credentialsId ?: null) as String     // GitHub App or PAT
    final String  repoOwner          = (cfg.owner ?: null) as String             // helps GitHub App pick installation
    final boolean createGithubRelease= (cfg.createGithubRelease == true)
    final boolean releaseDraft       = (cfg.releaseDraft == true)
    final boolean releasePrerelease  = (cfg.prerelease == true)
    final boolean generateNotes      = (cfg.generateReleaseNotes == false) ? false : true
    final String  githubApi          = (cfg.githubApi ?: 'https://api.github.com') as String

    // -------- Repo metadata --------
    sh "git fetch --tags --force --prune || true"
    String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    env.COMMIT_MESSAGE = commitMsg
    String head   = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    String branch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()

    // Pre-compute forced intent (affects skip-on-same-commit)
    String preForcedBump = (cfg.forceBump ?: '').toString().toLowerCase()
    if (!preForcedBump) {
        if (cfg.forceMajor == true || env.FORCE_MAJOR == 'true') preForcedBump = 'major'
        else if (cfg.forceMinor == true || env.FORCE_MINOR == 'true') preForcedBump = 'minor'
        else if (cfg.forcePatch == true || env.FORCE_PATCH == 'true') preForcedBump = 'patch'
    }
    boolean preForcedRelease = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
    boolean hasForcedIntent  = (preForcedBump in ['major','minor','patch']) || preForcedRelease

    // -------- Avoid double-bumps on identical commit --------
    if (fileExists(stateFile) && cfg.skipOnSameCommit != false && !hasForcedIntent) {
        String lastSha = readFile(stateFile).trim()
        if (lastSha == head) {
            String reuse = fileExists(versionFile) ? readFile(versionFile).trim() : readTagVersion(tagPattern, tagMode)
            env.BUILD_VERSION = reuse ?: '0.0.0'
            return [
                baseVersion    : env.BUILD_VERSION,
                baselineSource : fileExists(versionFile) ? 'file' : 'tag',
                version        : env.BUILD_VERSION,
                bump           : 'none',
                isRelease      : false,
                commitMessage  : commitMsg,
                skipped        : true,
                branch         : branch
            ]
        }
    }

    // -------- Baseline (tag/file/hybrid) --------
    String baselineSource = 'tag'
    String tagVer  = readTagVersion(tagPattern, tagMode)
    String fileVer = fileExists(versionFile) ? readFile(versionFile).trim() : '0.0.0'
    String current
    if (strategy == 'file') {
        current = fileVer
        baselineSource = 'file'
    } else if (strictTagBaseline) {
        current = tagVer
        baselineSource = 'tag'
    } else {
        current = maxSemver(tagVer ?: '0.0.0', fileVer ?: '0.0.0') // hybrid by default
        baselineSource = (current == (tagVer ?: '0.0.0')) ? 'tag' : 'file'
    }

    // -------- Parse & bump --------
    List<String> parts = (current ?: '0.0.0').tokenize('.')
    int M = parts.size() > 0 ? (parts[0] as int) : 0
    int m = parts.size() > 1 ? (parts[1] as int) : 0
    int p = parts.size() > 2 ? (parts[2] as int) : 0
    int origPatch = p

    String forcedBump = preForcedBump
    String bump = 'patch'
    boolean usedForcedBump = false
    switch (forcedBump) {
        case 'major': M++; m = 0; p = 0; bump = 'major'; usedForcedBump = true; break
        case 'minor': m++; p = 0; bump = 'minor'; usedForcedBump = true; break
        case 'patch': p++;         bump = 'patch'; usedForcedBump = true; break
        default:
            if (commitMsg.contains(majorToken))      { M++; m = 0; p = 0; bump = 'major' }
            else if (commitMsg.contains(minorToken)) { m++; p = 0; bump = 'minor' }
            else                                     { p++;         bump = 'patch' }
    }

    // Optional cumulative patch (only if baseline is a tag and this is a plain patch)
    int commitsSinceTag = 0
    if (cumulativePatch && baselineSource == 'tag' && bump == 'patch' && !usedForcedBump
        && !commitMsg.contains(majorToken) && !commitMsg.contains(minorToken)) {
        try {
            String lastTag = readNearestTag(tagPattern)
            if (lastTag) {
                commitsSinceTag = sh(script: "git rev-list --count ${lastTag}..HEAD", returnStdout: true).trim() as int
                if (commitsSinceTag > 0) p = origPatch + commitsSinceTag
            }
        } catch (Throwable ignored) { commitsSinceTag = 0 }
    }

    String version = "${M}.${m}.${p}"
    env.BUILD_VERSION = version

    // Persist version + state
    if (writeFileOut) writeFile file: versionFile, text: "${version}\n"
    writeFile file: stateFile, text: "${head}\n"

    // -------- Tag & GitHub Release (optional) --------
    boolean forcedRelease = preForcedRelease
    boolean isRelease = forcedRelease || commitMsg.contains(releaseToken)
    boolean canTagHere = !onlyTagOnMain || (branch == mainBranch)

    boolean shouldTag = canTagHere && (alwaysTag || (tagOnRelease && isRelease))
    boolean pushedTag = false
    boolean ghReleased = false

    if (shouldTag) {
        String tag = "v${version}"
        if (tagAlreadyExists(tag)) {
            echo "Tag ${tag} already exists; skipping creation"
        } else {
            sh "git tag -a ${tag} -m 'Release ${tag}'"
            if (pushTags) { pushTag(version, credentialsId, repoOwner); pushedTag = true }
        }
        if (createGithubRelease && credentialsId) {
            ghReleased = createOrUpdateRelease(version, credentialsId, githubApi, releaseDraft, releasePrerelease, generateNotes)
        }
    } else {
        echo "Tag gating → alwaysTag=${alwaysTag}, isRelease=${isRelease}, tagOnRelease=${tagOnRelease}, branch=${branch}, onlyTagOnMain=${onlyTagOnMain}"
    }

    return [
        baseVersion     : current,
        baselineSource  : baselineSource,   // 'tag' | 'file'
        version         : version,
        bump            : bump,
        isRelease       : isRelease,
        commitMessage   : commitMsg,
        forcedBump      : usedForcedBump ? forcedBump : null,
        forcedRelease   : forcedRelease,
        commitsSinceTag : commitsSinceTag,
        cumulativePatch : cumulativePatch,
        branch          : branch,
        tagPushed       : pushedTag,
        githubReleased  : ghReleased
    ]
}

// ---------------- Helpers (CPS-safe) ----------------

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
    List<String> as = (a ?: '0.0.0').tokenize('.')
    List<String> bs = (b ?: '0.0.0').tokenize('.')
    int A0 = as.size()>0 ? as[0] as int : 0; int A1 = as.size()>1 ? as[1] as int : 0; int A2 = as.size()>2 ? as[2] as int : 0
    int B0 = bs.size()>0 ? bs[0] as int : 0; int B1 = bs.size()>1 ? bs[1] as int : 0; int B2 = bs.size()>2 ? bs[2] as int : 0
    if (A0 != B0) return (A0 > B0) ? a : b
    if (A1 != B1) return (A1 > B1) ? a : b
    if (A2 != B2) return (A2 > B2) ? a : b
    return a
}

private Map detectOwnerRepo(String originUrl) {
    String url = originUrl ?: ''
    url = url.replaceAll(/\.git$/, '')
    if (url.startsWith('git@github.com:')) {
        url = 'https://github.com/' + url.substring('git@github.com:'.length())
    }
    def m = (url =~ /github\.com\/([^\/]+)\/([^\/]+)$/)
    if (m.find()) return [owner: m.group(1), repo: m.group(2)]
    return [owner: '', repo: '']
}

private String resolveGithubToken(String credentialsId, String ownerHint) {
    if (!credentialsId) return null
    // Try GitHub App token
    try {
        if (ownerHint) return githubAppToken(credentialsId: credentialsId, owner: ownerHint)
        return githubAppToken(credentialsId: credentialsId)
    } catch (Throwable ignore) {
        // fall through to PAT / user-pass
    }
    // Secret Text (PAT)
    try {
        def token = null
        withCredentials([string(credentialsId: credentialsId, variable: 'GITHUB_TOKEN_TMP')]) {
            token = env.GITHUB_TOKEN_TMP
        }
        if (token) return token
    } catch (Throwable ignore) {}
    // Username/Password (use password as token)
    try {
        def tok = null
        withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER_TMP', passwordVariable: 'GITHUB_PASS_TMP')]) {
            tok = env.GITHUB_PASS_TMP
        }
        if (tok) return tok
    } catch (Throwable ignore) {}
    return null
}

private void pushTag(String version, String credentialsId, String ownerHint) {
    String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    Map or = detectOwnerRepo(origin)
    String httpsRepo = origin
        .replaceFirst(/^git@github\.com:/, 'https://github.com/')
        .replaceFirst(/^https:\/\/[^@]+@github\.com\//, 'https://github.com/')

    String token = resolveGithubToken(credentialsId, ownerHint ?: or.owner)
    if (!token) {
        sh "git push origin v${version}"
        return
    }

    writeFile file: 'git-askpass.sh', text: '#!/bin/sh\necho "$GITHUB_TOKEN"\n'
    sh 'chmod 700 git-askpass.sh'
    def ask = "${pwd()}/git-askpass.sh"
    withEnv(["GIT_ASKPASS=${ask}", "GITHUB_TOKEN=${token}"]) {
        sh """
           git remote set-url origin https://x-access-token@${httpsRepo.replaceFirst(/^https:\\/\\//,'')}
           git push origin v${version}
        """
    }
}

private boolean createOrUpdateRelease(String version, String credentialsId, String apiBase, boolean draft, boolean prerelease, boolean genNotes) {
    String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    Map or = detectOwnerRepo(origin)
    String owner = or.owner
    String repo  = or.repo
    if (!owner || !repo) { echo "GitHub owner/repo not detected from origin, skipping release"; return false }

    String token = resolveGithubToken(credentialsId, owner)
    if (!token) { echo "No GitHub token available; skipping GitHub Release"; return false }

    String tag = "v${version}"
    String hdrs = "-H \"Authorization: Bearer ${token}\" -H \"Accept: application/vnd.github+json\""

    // Check if release exists
    String status = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()

    if (status == '200') {
        // Parse ID and PATCH
        String rid = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag} | sed -n 's/.*\"id\"\\s*:\\s*\\([0-9][0-9]*\\).*/\\1/p' | head -n1", returnStdout: true).trim()
        if (rid) {
            sh """curl -sS -X PATCH ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/${rid} \
                  -d '{"name":"${tag}","draft":${draft},"prerelease":${prerelease}}' >/dev/null"""
            return true
        }
        // fall through to create if parsing failed
    }

    // Create new release
    sh """curl -sS -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases \
          -d '{"tag_name":"${tag}","name":"${tag}","draft":${draft},"prerelease":${prerelease},"generate_release_notes":${genNotes}}' >/dev/null"""
    return true
}

private boolean tagAlreadyExists(String tag) {
    return (sh(script: "git rev-parse -q --verify refs/tags/${tag}", returnStatus: true) == 0)
}
