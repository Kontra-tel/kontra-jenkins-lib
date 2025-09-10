// vars/semver.groovy
//
// Computes a semantic version string and (optionally) writes version.txt.
// No git tags, no GitHub API calls.
//
// Tokens in last commit message:
//   !major  -> bump major
//   !minor  -> bump minor
//   !patch  -> bump patch (configurable)
//   (else)  -> defaultBump ('patch' by default; set to 'none' to require tokens)
//
// Strategy:
//   - 'file' : baseline from version.txt only
//   - 'tag'  : baseline from tags by default; if strictTagBaseline=false (default),
//              baseline = max( latest_tag , version.txt ) to keep bumps sticky across runs.
//
// Optional: cumulativePatch=true => patch += commits since last tag (only if baseline came from a tag).
//
// Prevents double-bumps on the same commit via a tiny state file (.semver-state).
//
// Returns: [baseVersion, baselineSource, version, bump, commitsSinceTag, cumulativePatch, commitMessage, branch, skipped]
//
def call(Map cfg = [:]) {
  // ---- Config (versioning only) ----
  final String  versionFile        = (cfg.versionFile ?: 'version.txt') as String
  final String  majorToken         = (cfg.majorToken  ?: '!major') as String
  final String  minorToken         = (cfg.minorToken  ?: '!minor') as String
  final String  patchToken         = (cfg.patchToken  ?: '!patch') as String
  final String  strategy           = (cfg.strategy ?: 'tag') as String              // 'tag' | 'file'
  final boolean strictTagBaseline  = (cfg.strictTagBaseline == true)               // if true: ignore version.txt for baseline
  final boolean cumulativePatch    = (cfg.cumulativePatch == true)
  final String  tagPattern         = (cfg.tagPattern ?: 'v[0-9]*') as String
  final String  tagMode            = (cfg.tagMode ?: 'nearest') as String          // 'nearest' | 'latest'
  final boolean writeFileOut       = (cfg.writeFile == false) ? false : true
  final String  stateFile          = (cfg.stateFile ?: '.semver-state') as String
  final String  defaultBump        = ((cfg.defaultBump ?: 'none') as String).toLowerCase() // 'patch' | 'none'

  // Optional forced bumps (overrides tokens)
  String forcedBump = (cfg.forceBump ?: '').toString().toLowerCase()
  if (!forcedBump) {
    if (cfg.forceMajor == true || env.FORCE_MAJOR == 'true') forcedBump = 'major'
    else if (cfg.forceMinor == true || env.FORCE_MINOR == 'true') forcedBump = 'minor'
    else if (cfg.forcePatch == true || env.FORCE_PATCH == 'true') forcedBump = 'patch'
  }

  // Release (pure metadata in this trimmed step) - treat !release or forceRelease flag as a release marker
  boolean isRelease = false
  if (cfg.forceRelease == true || env.FORCE_RELEASE == 'true') {
    isRelease = true
  }

  // ---- Repo metadata ----
  sh "git fetch --tags --force --prune || true"
  String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
  env.COMMIT_MESSAGE = commitMsg
  if (!isRelease && commitMsg.contains('!release')) {
    isRelease = true
  }
  String head   = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
  String branch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
  if (branch == 'HEAD' || !branch) {
    // Use Jenkins-provided env if available
    branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').trim()
  }
  if (branch == 'origin/HEAD') {
    branch = branch.replace('origin/HEAD', '').replace('origin/', '').trim()
  }
  if (!branch || branch == 'HEAD') {
    // Try to infer from branches containing HEAD
    try {
      String guess = sh(script: "git branch --contains HEAD 2>/dev/null | grep -v '(HEAD detached' | head -n1 | sed 's/* //' || true", returnStdout: true).trim()
      if (guess) branch = guess
    } catch (Throwable ignore) {}
  }
  if (!branch) branch = 'main'

  // Skip re-bump for same commit unless explicitly forced
  if (fileExists(stateFile) && cfg.skipOnSameCommit != false && forcedBump == '') {
    String lastSha = readFile(file: stateFile).trim()
    if (lastSha == head) {
      String reuse = fileExists(versionFile) ? readFile(file: versionFile).trim() : readTagVersion(tagPattern, tagMode)
      env.BUILD_VERSION = reuse ?: '0.0.0'
      return [
        baseVersion    : env.BUILD_VERSION,
        baselineSource : fileExists(versionFile) ? 'file' : 'tag',
        version        : env.BUILD_VERSION,
        bump           : 'none',
        commitsSinceTag: 0,
        cumulativePatch: cumulativePatch,
        commitMessage  : commitMsg,
        branch         : branch,
  skipped        : true,
  isRelease      : isRelease,
  forcedBump     : forcedBump ?: ''
      ]
    }
  }

  // ---- Baseline (tag/file/hybrid) ----
  String baselineSource = 'tag'
  String tagVer  = readTagVersion(tagPattern, tagMode)
  String fileVer = fileExists(versionFile) ? readFile(file: versionFile).trim() : '0.0.0'
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

  // ---- Parse & bump ----
  List<String> parts = (current ?: '0.0.0').tokenize('.')
  int M = parts.size() > 0 ? (parts[0] as int) : 0
  int m = parts.size() > 1 ? (parts[1] as int) : 0
  int p = parts.size() > 2 ? (parts[2] as int) : 0
  int origPatch = p

  String bump = 'patch'
  switch (forcedBump) {
    case 'major': M++; m = 0; p = 0; bump = 'major'; break
    case 'minor': m++; p = 0; bump = 'minor'; break
    case 'patch': p++;         bump = 'patch'; break
    default:
      if (commitMsg.contains(majorToken))      { M++; m = 0; p = 0; bump = 'major' }
      else if (commitMsg.contains(minorToken)) { m++; p = 0; bump = 'minor' }
      else if (commitMsg.contains(patchToken)) { p++;         bump = 'patch' }
      else if (defaultBump == 'patch')         { p++;         bump = 'patch' }
      else                                     {                 bump = 'none'  }
  }

  // Safety: if defaultBump requested patch but we still ended up with 'none', enforce patch
  if (bump == 'none' && defaultBump == 'patch') {
    p++
    bump = 'patch'
  }

  // Optional cumulative patch (only if baseline is a tag and this is a plain patch w/out forced bump)
  int commitsSinceTag = 0
  if (forcedBump == '' && cumulativePatch && baselineSource == 'tag' && bump == 'patch'
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
  env.IS_RELEASE = isRelease ? 'true' : 'false'

  if (writeFileOut) writeFile file: versionFile, text: "${version}\n"
  writeFile file: stateFile, text: "${head}\n"

  return [
    baseVersion    : current,
    baselineSource : baselineSource,   // 'tag' | 'file'
    version        : version,
    bump           : bump,
    commitsSinceTag: commitsSinceTag,
    cumulativePatch: cumulativePatch,
    commitMessage  : commitMsg,
    branch         : branch,
    skipped        : false,
    isRelease      : isRelease,
    forcedBump     : forcedBump ?: ''
  ]
}

// ---- helpers (CPS-safe) ----
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
  List<String> partsA = (a ?: '0.0.0').tokenize('.')
  List<String> partsB = (b ?: '0.0.0').tokenize('.')
  while (partsA.size() < 3) partsA << '0'
  while (partsB.size() < 3) partsB << '0'
  int A0 = partsA[0] as int
  int A1 = partsA[1] as int
  int A2 = partsA[2] as int
  int B0 = partsB[0] as int
  int B1 = partsB[1] as int
  int B2 = partsB[2] as int
  if (A0 != B0) return (A0 > B0) ? a : b
  if (A1 != B1) return (A1 > B1) ? a : b
  if (A2 != B2) return (A2 > B2) ? a : b
  return a
}
