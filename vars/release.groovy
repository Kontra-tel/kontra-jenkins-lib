// vars/release.groovy
//
// Creates/pushes a git tag (e.g. vX.Y.Z) and optionally a GitHub Release.
// Expects `version:` (string). Does NOT compute/bump versions.
//
// Tagging gates (independent of GH Release):
//   - alwaysTag:true                      -> tag every time
//   - tagOnRelease:true (default) & commit has '!release' OR forceRelease:true
//   - onlyTagOnMain:true (default)        -> allow tagging only on releaseBranch
//
// GitHub Release will be created when ALL are true:
//   - credentialsId provided (GitHub App or PAT) AND
//   - not explicitly suppressed by '!no-ghrelease' AND
//   - any of:
//       * cfg.createGithubRelease == true
//       * commit message contains '!ghrelease'
//       * cfg.forceGithubRelease == true  OR env.FORCE_GH_RELEASE == 'true'
//
// Additionally, if a GH Release is requested, this step ensures the tag exists
// (respecting branch gate) and pushes it before creating/updating the release.
//
// Returns: [tag, tagged, pushed, githubReleased, isRelease, ghReleaseRequested, branch]
//
def call(Map cfg = [:]) {
  // Required
  String version = (cfg.version ?: env.BUILD_VERSION ?: '').toString().trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  // Core config (provide safe defaults for all referenced symbols)
  final String  tagPrefix          = (cfg.tagPrefix ?: 'v') as String
  final String  releaseToken       = (cfg.releaseToken ?: '!release') as String
  final boolean tagOnRelease       = (cfg.tagOnRelease == false) ? false : true
  final boolean forceRelease       = (cfg.forceRelease == true)
  final boolean onlyTagOnMain      = (cfg.onlyTagOnMain == false) ? false : true
  final String  mainBranch         = (cfg.releaseBranch ?: cfg.mainBranch ?: 'main') as String
  final boolean alwaysTag          = (cfg.alwaysTag == true)

  // Tokens (support aliases for clarity)
  // Tagging now triggered by '!tag' (primary), GitHub Release by '!release' (primary)
  // Backward-compatible aliases remain supported.
  final List<String> releaseTokens     = (cfg.releaseTokens instanceof List ? cfg.releaseTokens : null) ?: ['!tag']
  final List<String> ghReleaseTokens   = (cfg.ghReleaseTokens instanceof List ? cfg.ghReleaseTokens : null) ?: ['!release', '!ghrelease', '!github-release']
  final List<String> ghReleaseNoTokens = (cfg.ghReleaseNoTokens instanceof List ? cfg.ghReleaseNoTokens : null) ?: ['!no-ghrelease', '!no-github-release']
  final String       ghReleaseToken    = (cfg.ghReleaseToken ?: '!release') as String  // primary token is now !release
  final String       ghReleaseNoToken  = (cfg.noGhReleaseToken ?: '!no-ghrelease') as String
  final boolean      forceGithubRelease= (cfg.forceGithubRelease == true) || (env.FORCE_GH_RELEASE == 'true')

  // Lightweight probe / connectivity
  final boolean pushTags            = (cfg.pushTags == false) ? false : true
  final String  credentialsId       = (cfg.credentialsId ?: null) as String
  final String  ownerHint           = (cfg.owner ?: null) as String
  final String  gitUserName         = (cfg.gitUserName ?: 'Jenkins CI') as String
  final String  gitUserEmail        = (cfg.gitUserEmail ?: 'jenkins@local') as String
  final boolean debug               = (cfg.debug == true)

  // GitHub Release details
  final boolean releaseDraft        = (cfg.releaseDraft == true)
  final boolean prerelease          = (cfg.prerelease == true)
  final boolean generateNotesFlag   = (cfg.generateReleaseNotes == true)   // API "generate_release_notes"
  final boolean attachCommitNotes   = (cfg.attachCommitNotes != false)     // our simple commit list (default on)
  final String  notesHeader         = (cfg.releaseNotesHeader ?: 'Changes since last release:') as String
  final String  githubApi           = (cfg.githubApi ?: 'https://api.github.com') as String

  // Repo facts
  String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
  String branch    = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
  if (branch == 'HEAD' || !branch) {
    branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').trim()
  }
  if (branch?.startsWith('origin/')) branch = branch.replaceFirst('^origin/', '')
  if (!branch || branch == 'HEAD') {
    try {
      String guess = sh(script: "git branch --contains HEAD 2>/dev/null | grep '^* ' | head -n1 | cut -c3- || true", returnStdout: true).trim()
      if (guess) branch = guess
    } catch (Throwable ignore) {}
  }
  if (!branch) branch = mainBranch

  String tag       = "${tagPrefix}${version}"

  // Decide if we should tag (only via tokens or force)
  boolean tokenWantsTag = commitMsg.contains(releaseToken)
  if (!tokenWantsTag) {
    for (String t : releaseTokens) { if (commitMsg.contains(t)) { tokenWantsTag = true; break } }
  }
  boolean isRelease   = forceRelease || tokenWantsTag
  boolean allowedBr   = !onlyTagOnMain || (branch == mainBranch)
  boolean shouldTag   = allowedBr && (alwaysTag || isRelease)

  // Decide if we should make a GitHub Release (strictly by token or force)
  boolean wantsGhByToken = commitMsg.contains(ghReleaseToken)
  if (!wantsGhByToken) {
    for (String t : ghReleaseTokens) { if (commitMsg.contains(t)) { wantsGhByToken = true; break } }
  }
  boolean noGhByToken    = commitMsg.contains(ghReleaseNoToken) || ghReleaseNoTokens.any { commitMsg.contains(it) }
  boolean ghReleaseRequested = !noGhByToken && (wantsGhByToken || forceGithubRelease)

  boolean tagged = false
  boolean pushed = false
  boolean ghRel  = false

  // Ensure a tag exists when explicitly requested via !release (wantsGhByToken), respecting branch gate
  boolean ensureTag = shouldTag || (wantsGhByToken && allowedBr)

  if (ensureTag) {
    // git identity
    sh "git config --local user.email '${gitUserEmail}' || true"
    sh "git config --local user.name  '${gitUserName}'  || true"

    if (tagAlreadyExists(tag)) {
      echo "release: tag ${tag} already exists locally; skipping creation"
      tagged = true
    } else {
      sh "git tag -a ${tag} -m 'Release ${tag}'"
      tagged = true
    }

    if (pushTags) {
      pushTag(tag, credentialsId, ownerHint, githubApi, debug)
      pushed = true
    }
  } else {
    echo "release: tag creation gated off → branch=${branch} onlyTagOnMain=${onlyTagOnMain} alwaysTag=${alwaysTag} isRelease=${isRelease}"
  }

  if (ghReleaseRequested && credentialsId) {
    // Require the tag to exist on the remote; do not let GH release creation implicitly create the tag
    if (!remoteTagExists(tag)) {
      echo "release: GH release requested but tag ${tag} not found on origin; push the tag first (use !release)"
      return [
        tag                : tag,
        tagged             : tagged,
        pushed             : pushed,
        githubReleased     : false,
        isRelease          : isRelease,
        ghReleaseRequested : ghReleaseRequested,
        branch             : branch
      ]
    }
    ghRel = createOrUpdateRelease(
      tag, credentialsId, githubApi,
      releaseDraft, prerelease,
      generateNotesFlag, attachCommitNotes, notesHeader, tagPrefix
    )
  } else if (ghReleaseRequested && !credentialsId) {
    echo "release: GitHub release requested but no credentialsId provided — skipping GH Release"
  }

  return [
    tag                : tag,
    tagged             : tagged,
    pushed             : pushed,
    githubReleased     : ghRel,
    isRelease          : isRelease,
    ghReleaseRequested : ghReleaseRequested,
    branch             : branch
  ]
}

// ---------- helpers ----------

private boolean tagAlreadyExists(String tag) {
  return (sh(script: "git rev-parse -q --verify refs/tags/${tag}", returnStatus: true) == 0)
}

private boolean remoteTagExists(String tag) {
  try {
    String out = sh(script: "git ls-remote --tags origin refs/tags/${tag} | wc -l", returnStdout: true).trim()
    return (out as int) > 0
  } catch (Throwable ignore) { return false }
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
  // Try GitHub App token (requires GitHub Branch Source plugin + installed App)
  try {
    if (ownerHint) return githubAppToken(credentialsId: credentialsId, owner: ownerHint)
    return githubAppToken(credentialsId: credentialsId)
  } catch (Throwable ignore) {}
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

private void pushTag(String tag, String credentialsId, String ownerHint, String apiBase, boolean debug=false) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String httpsRepo = origin
    .replaceFirst(/^git@github\.com:/, 'https://github.com/')
    .replaceFirst(/^https:\/\/[^@]+@github\.com\//, 'https://github.com/')

  String token = resolveGithubToken(credentialsId, ownerHint ?: or.owner)
  if (debug) {
    String prefix = token ? token.substring(0, Math.min(7, token.length())) + '…' : 'none'
    echo "release: pushTag repo=${or.owner}/${or.repo} credId=${credentialsId ?: 'none'} token=${prefix}"
  }

  if (!token) {
    int rc = sh(script: "git push origin ${tag}", returnStatus: true)
    if (rc != 0) {
      echo "release: push failed (status=${rc}) without token. Ensure Jenkins has push rights (deploy key with write or PAT)."
      error "release: git push failed (no token)"
    }
    return
  }

  // HTTPS push with App/PAT token
  writeFile file: 'git-askpass.sh', text: '#!/bin/sh\necho "$GITHUB_TOKEN"\n'
  sh 'chmod 700 git-askpass.sh'
  def ask = "${pwd()}/git-askpass.sh"
  withEnv(["GIT_ASKPASS=${ask}", "GITHUB_TOKEN=${token}"]) {
    int rc = sh(script: """
       set -e
       git remote set-url origin https://x-access-token:${token}@${httpsRepo.replaceFirst(/^https:\\/\\//,'')}
       GIT_CURL_VERBOSE=${debug ? 1 : 0} git push origin ${tag}
    """.stripIndent(), returnStatus: true)
    if (rc != 0) {
      echo "release: push failed (status=${rc}). Token may lack permissions or protected tags block creation."
      error "release: git push failed"
    }
  }
}

private boolean createOrUpdateRelease(
  String tag, String credentialsId, String apiBase,
  boolean draft, boolean prerelease,
  boolean generateNotesFlag, boolean attachCommitNotes, String notesHeader, String tagPrefix
) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String owner = or.owner
  String repo  = or.repo
  if (!owner || !repo) { echo "release: owner/repo not detected from origin; skipping GitHub Release"; return false }

  String token = resolveGithubToken(credentialsId, owner)
  if (!token) { echo "release: no GitHub token; skipping GitHub Release"; return false }

  // Build "since last release" notes if requested
  String headSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
  String prevTag = sh(
    script: "git -c versionsort.suffix=- tag -l '${tagPrefix}[0-9]*' --sort=-v:refname | sed -n '2p' || true",
    returnStdout: true
  ).trim()
  String commitRange = prevTag ? "${prevTag}..HEAD" : ""
  String changes = commitRange ? sh(
    script: "git log --no-merges --pretty='- %s (%h)' ${commitRange}",
    returnStdout: true
  ).trim() : ""
  String body = ""
  if (attachCommitNotes) {
    String header = prevTag ? "${notesHeader} (${prevTag} → ${tag})" : "${notesHeader}"
    // Use real newlines so GitHub renders the list correctly
    body = header + "\n\n" + (changes ? changes : "- (no user-visible changes)")
  }

  // If we craft a body, we cannot ALSO ask GitHub to auto-generate notes.
  boolean useGenerateNotes = generateNotesFlag && !attachCommitNotes

  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"

  // Does a release already exist for this tag?
  String status = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()

  if (status == '200') {
    // Update existing release
    String bodyJson = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()
    def m = (bodyJson =~ /\"id\"\s*:\s*(\d+)/)
    String rid = m.find() ? m.group(1) : ''
    if (rid) {
      Map patchPayload = [name: tag, draft: draft, prerelease: prerelease]
      if (attachCommitNotes && body) { patchPayload.body = body }
      String patchJson = groovy.json.JsonOutput.toJson(patchPayload)
      writeFile file: 'gh-release-patch.json', text: patchJson
      int rcPatch = sh(script: "curl -sS -X PATCH ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/${rid} -d @gh-release-patch.json >/dev/null", returnStatus: true)
      return (rcPatch == 0)
    }
  }

  // Create new release
  Map createPayload = [tag_name: tag, name: tag, draft: draft, prerelease: prerelease, target_commitish: headSha]
  if (attachCommitNotes && body) {
    createPayload.body = body
  } else if (useGenerateNotes) {
    createPayload.generate_release_notes = true
  }
  String createJson = groovy.json.JsonOutput.toJson(createPayload)
  writeFile file: 'gh-release.json', text: createJson
  int rcPost = sh(script: "curl -sS -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases -d @gh-release.json >/dev/null", returnStatus: true)
  return (rcPost == 0)
}
