// vars/release.groovy
//
// Creates/pushes a git tag (e.g. vX.Y.Z) and optionally a GitHub Release.
// Expects `version:` (string). Does NOT compute/bump versions.
//
// Gates:
//   - alwaysTag:true                     -> tag every time
//   - tagOnRelease:true & commit has '!release' OR forceRelease:true
//   - onlyTagOnMain:true                 -> allow tagging only on mainBranch
//
// GitHub:
//   - Provide credentialsId (GitHub App or PAT) to push tags and create releases.
//   - createGithubRelease:true to create/update a GitHub Release.
//
// Returns: [tag, tagged, pushed, githubReleased, isRelease, branch]
//
def call(Map cfg = [:]) {
  String version = (cfg.version ?: env.BUILD_VERSION ?: '').toString().trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  // Core config
  final String  tagPrefix          = (cfg.tagPrefix ?: 'v') as String
  final String  releaseToken       = (cfg.releaseToken ?: '!release') as String
  final boolean tagOnRelease       = (cfg.tagOnRelease == false) ? false : true
  final boolean forceRelease       = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
  final boolean onlyTagOnMain      = (cfg.onlyTagOnMain == false) ? false : true
  final String  mainBranch         = (cfg.mainBranch ?: cfg.releaseBranch ?: 'main') as String
  final boolean alwaysTag          = (cfg.alwaysTag == true)

  // Auth / push
  final boolean pushTags           = (cfg.pushTags == false) ? false : true
  final String  credentialsId      = (cfg.credentialsId ?: null) as String
  final String  ownerHint          = (cfg.owner ?: cfg.ownerHint ?: null) as String // accept both
  final String  gitUserName        = (cfg.gitUserName ?: 'Jenkins CI') as String
  final String  gitUserEmail       = (cfg.gitUserEmail ?: 'jenkins@local') as String
  final boolean debug              = (cfg.debug == true || (binding.hasVariable('params') && params.DEBUG_RELEASE == true))

  // GitHub Release settings
  final boolean createGithubRelease = (cfg.createGithubRelease == true)
  final boolean releaseDraft        = (cfg.releaseDraft == true)
  final boolean prerelease          = (cfg.prerelease == true)
  final boolean generateNotes       = (cfg.generateReleaseNotes == false) ? false : true
  final String  githubApi           = (cfg.githubApi ?: 'https://api.github.com') as String

  // Repo facts
  String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
  String branch    = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
  if (branch == 'HEAD' || !branch) branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').trim()
  if (branch?.startsWith('origin/')) branch = branch.replaceFirst('^origin/', '')
  if (!branch || branch == 'HEAD') {
    try {
      String guess = sh(script: "git branch --contains HEAD 2>/dev/null | grep '^* ' | head -n1 | cut -c3- || true", returnStdout: true).trim()
      if (guess) branch = guess
    } catch (Throwable ignore) {}
  }
  if (!branch) branch = 'main'

  String tag = "${tagPrefix}${version}"

  // Should we tag?
  boolean isRelease = forceRelease || (tagOnRelease && commitMsg.contains(releaseToken))
  boolean allowedBr = !onlyTagOnMain || (branch == mainBranch)
  boolean shouldTag = allowedBr && (alwaysTag || isRelease)

  boolean tagged = false
  boolean pushed = false
  boolean ghRel  = false

  if (shouldTag) {
    // Ensure identity
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
      pushed = pushOrCreateTag(tag, credentialsId, ownerHint, githubApi, gitUserName, gitUserEmail, debug)
    }

    if (createGithubRelease && credentialsId) {
      ghRel = createOrUpdateRelease(tag, credentialsId, githubApi, releaseDraft, prerelease, generateNotes)
    }
  } else {
    echo "release: gated off → branch=${branch} onlyTagOnMain=${onlyTagOnMain} alwaysTag=${alwaysTag} isRelease=${isRelease}"
  }

  return [tag: tag, tagged: tagged, pushed: pushed, githubReleased: ghRel, isRelease: isRelease, branch: branch]
}

// ---------- helpers ----------

private boolean tagAlreadyExists(String tag) {
  return (sh(script: "git rev-parse -q --verify refs/tags/${tag}", returnStatus: true) == 0)
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
  // GitHub App (Branch Source plugin)
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

private boolean remoteTagExistsAPI(String owner, String repo, String token, String apiBase, String tag) {
  if (!token || !owner || !repo) return false
  String code = sh(script: "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' ${apiBase}/repos/${owner}/${repo}/git/refs/tags/${tag}", returnStdout: true).trim()
  return code == '200'
}

private boolean pushOrCreateTag(String tag, String credentialsId, String ownerHint, String apiBase, String gitUserName, String gitUserEmail, boolean debug=false) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String httpsRepo = origin
    .replaceFirst(/^git@github\.com:/, 'https://github.com/')
    .replaceFirst(/^https:\/\/[^@]+@github\.com\//, 'https://github.com/')

  String token = resolveGithubToken(credentialsId, ownerHint ?: or.owner)
  String obf = token ? (token.startsWith('gh') ? token.substring(0, token.indexOf('_') > 0 ? token.indexOf('_')+1 : Math.min(6, token.length())) + '…' : '***') : 'none'

  if (debug) {
    echo "release: pushTag repo=${or.owner}/${or.repo} credId=${credentialsId ?: 'none'} token=${obf}"
    try {
      String who = token?.startsWith('ghs_') ? sh(script: "curl -s -H 'Authorization: Bearer ${token}' ${apiBase}/app | jq -r '.slug // .name // \"app\"' 2>/dev/null || true", returnStdout: true).trim() : sh(script: "curl -s -H 'Authorization: Bearer ${token}' ${apiBase}/user | jq -r '.login // \"user\"' 2>/dev/null || true", returnStdout: true).trim()
      if (who) echo "release: token actor=${who}"
    } catch (Throwable ignore) {}
    // Probe perms and tag rules
    try {
      String perms = sh(script: "curl -s -H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' ${apiBase}/repos/${or.owner}/${or.repo}", returnStdout: true).trim()
      echo "release: repo perms snippet: ${perms.replaceAll(/\\s+/,' ').take(200)}…"
      String rules = sh(script: "curl -s -H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' '${apiBase}/repos/${or.owner}/${or.repo}/rulesets?target=tag'", returnStdout: true).trim()
      echo "release: tag rulesets present: ${rules ? rules.replaceAll(/\\s+/,' ').take(200)+'…' : 'none'}"
    } catch (Throwable ignore) { echo 'release: permission/ruleset probe failed (ignored)' }
  }

  // If the tag already exists remotely, we're done.
  if (remoteTagExistsAPI(or.owner, or.repo, token, apiBase, tag)) {
    if (debug) echo "release: remote tag ${tag} already exists; skipping push"
    return true
  }

  if (!token) {
    // Try agent’s auth (deploy key, cached creds)
    int rc0 = sh(script: "git push origin ${tag}", returnStatus: true)
    if (rc0 == 0) return true
    echo "release: push failed (status=${rc0}) without token. Configure a PAT or GitHub App credential with write + (if needed) ruleset bypass."
    error "release: git push failed (no token)"
  }

  // Try a normal git push using token in remote URL (works with PATs and most GitHub App tokens)
  String hostPath = httpsRepo.replaceFirst(/^https:\\/\\//, '')
  int rc = sh(script: """
    set -e
    git remote set-url origin https://x-access-token:${token}@${hostPath}
    GIT_CURL_VERBOSE=${debug ? 1 : 0} git push origin ${tag}
  """.stripIndent(), returnStatus: true)

  if (rc == 0) return true

  // Fallback: create the annotated tag via GitHub REST API (respects rulesets & bypass lists)
  echo "release: git push blocked (status=${rc}); trying REST API fallback to create tag ${tag}"
  boolean apiOk = createTagViaAPI(or.owner, or.repo, token, apiBase, tag, gitUserName, gitUserEmail)
  if (apiOk) {
    echo "release: tag ${tag} created via REST API"
    return true
  }

  echo "release: API fallback failed. Either token lacks Contents: write or tag ruleset blocks creation for this actor."
  error "release: tag creation failed"
}

private boolean createTagViaAPI(String owner, String repo, String token, String apiBase, String tag, String gitUserName, String gitUserEmail) {
  if (!owner || !repo || !token) return false

  // If it already exists remotely, treat as success
  String exists = sh(script: "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' ${apiBase}/repos/${owner}/${repo}/git/refs/tags/${tag}", returnStdout: true).trim()
  if (exists == '200') return true

  String commitSha = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  String iso = sh(script: "date -u +%Y-%m-%dT%H:%M:%SZ", returnStdout: true).trim()
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"

  // 1) Create annotated tag object
  String payloadTag = """{"tag":"${tag}","message":"Release ${tag}","object":"${commitSha}","type":"commit","tagger":{"name":"${gitUserName}","email":"${gitUserEmail}","date":"${iso}"}}"""
  String createTagJson = sh(script: "curl -sS -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/tags -d '${payloadTag}'", returnStdout: true).trim()
  String tagObjSha = sh(script: "printf %s '${createTagJson}' | sed -n 's/.*\"sha\"\\s*:\\s*\"\\([0-9a-f]\\{40\\}\\)\".*/\\1/p' | head -n1", returnStdout: true).trim()
  if (!tagObjSha) {
    // If 422 "already_exists" we’ll assume the tag object exists; proceed to create ref
    String code = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/tags/${tag}", returnStdout: true).trim()
    if (code != '200') return false
    tagObjSha = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/tags/${tag} | sed -n 's/.*\"sha\"\\s*:\\s*\"\\([0-9a-f]\\{40\\}\\)\".*/\\1/p' | head -n1", returnStdout: true).trim()
    if (!tagObjSha) return false
  }

  // 2) Create ref pointing to tag object
  String payloadRef = """{"ref":"refs/tags/${tag}","sha":"${tagObjSha}"}"""
  String refCode = sh(script: "curl -s -o /dev/null -w '%{http_code}' -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/refs -d '${payloadRef}'", returnStdout: true).trim()
  if (refCode == '201' || refCode == '200') return true
  if (refCode == '422') {
    // Reference already exists
    return true
  }
  return false
}

private boolean createOrUpdateRelease(String tag, String credentialsId, String apiBase, boolean draft, boolean prerelease, boolean genNotes) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String owner = or.owner
  String repo  = or.repo
  if (!owner || !repo) { echo "release: owner/repo not detected from origin; skipping GitHub Release"; return false }

  String token = resolveGithubToken(credentialsId, owner)
  if (!token) { echo "release: no GitHub token; skipping GitHub Release"; return false }

  String hdrs = "-H \"Authorization: Bearer ${token}\" -H \"Accept: application/vnd.github+json\""
  String status = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()

  if (status == '200') {
    String rid = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag} | sed -n 's/.*\"id\"\\s*:\\s*\\([0-9][0-9]*\\).*/\\1/p' | head -n1", returnStdout: true).trim()
    if (rid) {
      sh """curl -sS -X PATCH ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/${rid} \
            -d '{"name":"${tag}","draft":${draft},"prerelease":${prerelease}}' >/dev/null"""
      return true
    }
  }
  sh """curl -sS -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases \
        -d '{"tag_name":"${tag}","name":"${tag}","draft":${draft},"prerelease":${prerelease},"generate_release_notes":${genNotes}}' >/dev/null"""
  return true
}
