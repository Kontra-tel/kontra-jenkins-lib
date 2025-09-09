// vars/release.groovy
//
// Minimal tag + GitHub Release step (prefers GitHub App credentials).
// - Gates tagging by: alwaysTag OR (!release / forceRelease), plus onlyTagOnMain.
// - Creates annotated tag and pushes it using a GitHub App installation token (ghs_*), or PAT fallback.
// - Creates/updates a GitHub Release; by default includes "notes since previous tag".
//
// Inputs (common):
//   version                : String (required if env.BUILD_VERSION not set)
//   tagPrefix              : default 'v'
//   releaseToken           : default '!release'
//   tagOnRelease           : default true
//   forceRelease           : default false
//   alwaysTag              : default false
//   onlyTagOnMain          : default true
//   releaseBranch          : default 'main'
//   gitUserName/gitUserEmail: identity for tag annotation
//
// GitHub:
//   credentialsId          : Jenkins credential (GitHub App recommended)  <-- IMPORTANT
//   owner                  : org/user hint for githubAppToken (optional but recommended for org repos)
//   createGithubRelease    : default false
//   releaseDraft           : default false
//   prerelease             : default false
//   notesSinceLastTag      : default true  (compose body from git log)
//   generateReleaseNotes   : default true  (let GitHub auto-generate when no body provided)
//   githubApi              : default 'https://api.github.com'
//
// Returns: [tag, tagged, pushed, githubReleased, branch, releaseUrl]

def call(Map cfg = [:]) {
  // ----- Inputs & defaults
  final String version       = (cfg.version ?: env.BUILD_VERSION ?: '').trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  final String tagPrefix     = (cfg.tagPrefix ?: 'v') as String
  final String tag           = "${tagPrefix}${version}"

  final boolean forceRelease = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
  final boolean tagOnRelease = (cfg.tagOnRelease != false)
  final String  releaseToken = (cfg.releaseToken ?: '!release') as String
  final boolean alwaysTag    = (cfg.alwaysTag == true)

  final boolean onlyTagOnMain = (cfg.onlyTagOnMain != false)
  final String  mainBranch    = (cfg.releaseBranch ?: 'main') as String

  final String  gitUserName   = (cfg.gitUserName ?: 'Jenkins CI') as String
  final String  gitUserEmail  = (cfg.gitUserEmail ?: 'jenkins@local') as String

  // GitHub knobs
  final String  credentialsId       = (cfg.credentialsId ?: null) as String
  final String  ownerHint           = (cfg.owner ?: null) as String
  final boolean createGithubRelease = (cfg.createGithubRelease == true)
  final boolean releaseDraft        = (cfg.releaseDraft == true)
  final boolean prerelease          = (cfg.prerelease == true)
  final boolean notesSinceLastTag   = (cfg.notesSinceLastTag != false) // default: true
  final boolean autoGenNotes        = (cfg.generateReleaseNotes == true)
  final String  githubApi           = (cfg.githubApi ?: 'https://api.github.com') as String

  // ----- Repo facts
  sh "git fetch --tags --force --prune || true"
  String commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
  String branch    = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
  if (!branch || branch == 'HEAD') {
    branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: mainBranch).trim()
  }

  // ----- Should we tag?
  boolean isRelease = forceRelease || (tagOnRelease && commitMsg.contains(releaseToken))
  boolean allowedBr = !onlyTagOnMain || (branch == mainBranch)
  boolean shouldTag = allowedBr && (alwaysTag || isRelease)

  boolean tagged = false
  boolean pushed = false
  boolean ghRel  = false
  String  ghReleaseUrl = ''

  // Ensure identity for local tag
  sh "git config --local user.email '${gitUserEmail}' || true"
  sh "git config --local user.name  '${gitUserName}'  || true"

  // ----- Tag + push
  if (shouldTag) {
    if (!tagExistsLocal(tag)) {
      sh "git tag -a ${tag} -m 'Release ${tag}'"
    }
    tagged = true

    // Prefer GitHub App token; fallback to PAT/Secret Text
    if (credentialsId) {
      String token = resolveTokenPreferApp(credentialsId, ownerHint)
      int rc = pushTagWithToken(tag, token)
      if (rc != 0) error "release: git push failed (status=${rc})"
      pushed = true
    } else {
      int rc = sh(script: "git push origin ${tag}", returnStatus: true)
      if (rc != 0) error "release: git push failed (status=${rc})"
      pushed = true
    }
  } else {
    echo "release: tagging gated off → branch=${branch}, onlyTagOnMain=${onlyTagOnMain}, alwaysTag=${alwaysTag}, isRelease=${isRelease}"
  }

  // ----- GitHub Release (works whether tag existed already or we just created it)
  if (createGithubRelease && credentialsId) {
    def or = detectOwnerRepo(sh(script: 'git config --get remote.origin.url', returnStdout: true).trim())
    String body = ''
    if (notesSinceLastTag) {
      String prev = previousTagLike(tagPrefix, tag)
      if (prev) body = notesBetween(prev, tag, or.owner, or.repo)
    }
    String apiToken = resolveTokenPreferApp(credentialsId, ownerHint ?: or.owner)
    def rel = upsertRelease(or.owner, or.repo, tag, body, releaseDraft, prerelease, body ? false : autoGenNotes, apiToken, githubApi)
    ghRel = rel.ok
    ghReleaseUrl = rel.url ?: ''
  }

  return [tag: tag, tagged: tagged, pushed: pushed, githubReleased: ghRel, branch: branch, releaseUrl: ghReleaseUrl]
}

// ===== helpers =====

private boolean tagExistsLocal(String tag) {
  return (sh(script: "git rev-parse -q --verify refs/tags/${tag}", returnStatus: true) == 0)
}

private Map detectOwnerRepo(String originUrl) {
  String url = (originUrl ?: '').replaceAll(/\.git$/, '')
  if (url.startsWith('git@github.com:')) url = 'https://github.com/' + url.substring('git@github.com:'.length())
  def m = (url =~ /github\.com\/([^\/]+)\/([^\/]+)$/)
  return m.find() ? [owner: m.group(1), repo: m.group(2)] : [owner: '', repo: '']
}

/** Prefer GitHub App installation token; fallback to PAT/Secret Text/UsernamePassword. */
private String resolveTokenPreferApp(String credentialsId, String ownerHint = null) {
  // 1) GitHub App installation token
  try {
    if (ownerHint) return githubAppToken(credentialsId: credentialsId, owner: ownerHint)
    return githubAppToken(credentialsId: credentialsId)
  } catch (Throwable ignore) {}
  // 2) Secret Text (PAT)
  try {
    def tok = null
    withCredentials([string(credentialsId: credentialsId, variable: 'GITHUB_TOKEN_TMP')]) { tok = env.GITHUB_TOKEN_TMP }
    if (tok) return tok
  } catch (Throwable ignore) {}
  // 3) Username/Password → use password as token
  try {
    def tok = null
    withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER_TMP', passwordVariable: 'GITHUB_PASS_TMP')]) { tok = env.GITHUB_PASS_TMP }
    if (tok) return tok
  } catch (Throwable ignore) {}
  return null
}

/** Push the tag using HTTPS with 'x-access-token' as username and token as password. */
private int pushTagWithToken(String tag, String token) {
  if (!token) return 128
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  String httpsRepo = origin
      .replaceFirst(/^git@github\.com:/, 'https://github.com/')
      .replaceFirst(/^https:\/\/[^@]+@github\.com\//, 'https://github.com/')
  return sh(script: """
    set -e
    git remote set-url origin https://x-access-token:${token}@${httpsRepo.replaceFirst(/^https:\\/\\//,'')}
    git push origin ${tag}
  """.stripIndent(), returnStatus: true)
}

private String previousTagLike(String prefix, String currentTag) {
  String prev = sh(script: "git describe --tags --abbrev=0 --match '${prefix}[0-9]*' --exclude '${currentTag}' 2>/dev/null || true", returnStdout: true).trim()
  if (prev) return prev
  String list = sh(script: "git -c versionsort.suffix=- tag -l '${prefix}[0-9]*' --sort=-v:refname", returnStdout: true).trim()
  def tags = list.readLines().findAll { it != currentTag }
  return tags ? tags[0].trim() : ''
}

private String notesBetween(String fromTag, String toTag, String owner, String repo) {
  String log = sh(script: "git log --no-merges --format='%h%x09%an%x09%s' ${fromTag}..${toTag} || true", returnStdout: true).trim()
  if (!log) return "No changes.\n\n[Compare ${fromTag}...${toTag}](https://github.com/${owner}/${repo}/compare/${fromTag}...${toTag})"
  def lines = log.readLines().collect { ln ->
    def p = ln.split('\\t', 3)
    (p.size() == 3) ? "- ${p[2]} (`${p[0]}` by ${p[1]})" : "- ${ln}"
  }
  return "### Changes since ${fromTag}\n\n${lines.join('\n')}\n\n[Compare ${fromTag}...${toTag}](https://github.com/${owner}/${repo}/compare/${fromTag}...${toTag})"
}

/** Create or update a GitHub Release for the tag. */
private Map upsertRelease(String owner, String repo, String tag, String body, boolean draft, boolean prerelease, boolean genNotes, String token, String apiBase) {
  if (!owner || !repo) { echo "release: owner/repo not detected; skip GitHub Release"; return [ok:false] }
  if (!token)          { echo "release: no token; skip GitHub Release"; return [ok:false] }

  String H = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"

  // If exists → PATCH
  String rc = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${H} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()
  if (rc == '200') {
    String json = sh(script: "curl -s ${H} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()
    String id   = json.replaceAll('.*\"id\"\\s*:\\s*([0-9]+).*', '$1')
    String url  = json.replaceAll('.*\"html_url\"\\s*:\\s*\"([^\"]+)\".*', '$1')
    String payload = body ? "{\"name\":\"${tag}\",\"body\":${toJson(body)},\"draft\":${draft},\"prerelease\":${prerelease}}"
                          : "{\"name\":\"${tag}\",\"draft\":${draft},\"prerelease\":${prerelease}}"
    sh "curl -sS -X PATCH ${H} ${apiBase}/repos/${owner}/${repo}/releases/${id} -d '${payload}' >/dev/null"
    return [ok:true, url:url]
  }

  // Create
  String target = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  String payload = body
    ? "{\"tag_name\":\"${tag}\",\"target_commitish\":\"${target}\",\"name\":\"${tag}\",\"body\":${toJson(body)},\"draft\":${draft},\"prerelease\":${prerelease},\"generate_release_notes\":false}"
    : "{\"tag_name\":\"${tag}\",\"target_commitish\":\"${target}\",\"name\":\"${tag}\",\"draft\":${draft},\"prerelease\":${prerelease},\"generate_release_notes\":${genNotes}}"

  String resp = sh(script: "curl -sS -X POST ${H} ${apiBase}/repos/${owner}/${repo}/releases -d '${payload}'", returnStdout: true).trim()
  String url  = resp.replaceAll('.*\"html_url\"\\s*:\\s*\"([^\"]+)\".*', '$1')
  return [ok: resp.contains('\"id\"') && url, url: url]
}

private String toJson(String s) {
  '"' + (s ?: '').replace('\\','\\\\').replace('"','\\"').replace('\n','\\n') + '"'
}
