// vars/release.groovy
//
// Creates/pushes a git tag (e.g. vX.Y.Z) and optionally a GitHub Release.
// Expects `version:` (string). Does NOT compute/bump versions.
//
// Behavior gates:
//   - alwaysTag:true                      -> tag every time
//   - tagOnRelease:true (default) & commit has '!release' OR forceRelease:true
//   - onlyTagOnMain:true (default)        -> allow tagging only on releaseBranch
//
// GitHub:
//   - Provide credentialsId (GitHub App or PAT) to push tags and create releases.
//   - createGithubRelease:true to create/update a GitHub Release.
//
// Returns: [tag, tagged, pushed, githubReleased, isRelease, branch]
//
def call(Map cfg = [:]) {
  // Required
  String version = (cfg.version ?: env.BUILD_VERSION ?: '').toString().trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  // Config
  final String  tagPrefix           = (cfg.tagPrefix ?: 'v') as String
  final boolean alwaysTag           = (cfg.alwaysTag == true)
  final boolean tagOnRelease        = (cfg.tagOnRelease == false) ? false : true
  final boolean forceRelease        = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
  final String  releaseToken        = (cfg.releaseToken ?: '!release') as String

  final boolean onlyTagOnMain       = (cfg.onlyTagOnMain == false) ? false : true
  final String  mainBranch          = (cfg.releaseBranch ?: 'main') as String

  final boolean pushTags            = (cfg.pushTags == false) ? false : true
  final String  credentialsId       = (cfg.credentialsId ?: null) as String
  final String  ownerHint           = (cfg.owner ?: null) as String
  final String  gitUserName         = (cfg.gitUserName ?: 'Jenkins CI') as String
  final String  gitUserEmail        = (cfg.gitUserEmail ?: 'jenkins@local') as String
  final boolean debug               = (cfg.debug == true)

  final boolean createGithubRelease = (cfg.createGithubRelease == true)
  final boolean releaseDraft        = (cfg.releaseDraft == true)
  final boolean prerelease          = (cfg.prerelease == true)
  final boolean generateNotes       = (cfg.generateReleaseNotes == false) ? false : true
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
      // Safe branch name extraction: line starting with '* ' then remove marker
      String guess = sh(script: "git branch --contains HEAD 2>/dev/null | grep '^* ' | head -n1 | cut -c3- || true", returnStdout: true).trim()
      if (guess) branch = guess
    } catch (Throwable ignore) {}
  }
  if (!branch) branch = 'main'

  String tag = "${tagPrefix}${version}"

  // Decide if we should tag
  boolean isRelease = forceRelease || (tagOnRelease && commitMsg.contains(releaseToken))
  boolean allowedBr = !onlyTagOnMain || (branch == mainBranch)
  boolean shouldTag = allowedBr && (alwaysTag || isRelease)

  boolean tagged = false
  boolean pushed = false
  boolean ghRel  = false

  if (shouldTag) {
    // Ensure git identity (avoid "unable to auto-detect email address")
    sh """
      set -eu
      if ! git config user.email >/dev/null 2>&1 || [ -z "\$(git config user.email || true)" ]; then
        git config user.email '${gitUserEmail}'
      fi
      if ! git config user.name  >/dev/null 2>&1 || [ -z "\$(git config user.name || true)" ]; then
        git config user.name '${gitUserName}'
      fi
    """

    if (tagAlreadyExists(tag)) {
      echo "release: tag ${tag} already exists; skipping creation"
      tagged = true
    } else {
      sh "git tag -a ${tag} -m 'Release ${tag}'"
      tagged = true
    }

    if (pushTags) {
      pushTag(tag, credentialsId, ownerHint, githubApi, debug)
      pushed = true
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
  // Try GitHub App token (requires GitHub Branch Source plugin and an installed App)
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

/**
 * Preflight probe: does this token SEE the repo and have push permission?
 * Returns [code: "200"/..., push: true|false]
 */
private Map ghProbeRepoAccess(String token, String owner, String repo, String apiBase) {
  String hdrs = "-H \"Authorization: Bearer ${token}\" -H \"Accept: application/vnd.github+json\""
  String code = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}", returnStdout: true).trim()
  String body = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}", returnStdout: true).trim()
  boolean pushAllowed = (body =~ /\"permissions\"\\s*:\\s*\\{[^}]*\"push\"\\s*:\\s*true/).find()
  return [code: code, push: pushAllowed]
}

private void pushTag(String tag, String credentialsId, String ownerHint, String apiBase, boolean debug=false) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String httpsRepo = origin
    .replaceFirst(/^git@github\.com:/, 'https://github.com/')
    .replaceFirst(/^https:\/\/[^@]+@github\.com\//, 'https://github.com/')

  String token = resolveGithubToken(credentialsId, ownerHint ?: or.owner)
  if (debug) {
    echo "release: pushTag origin=${httpsRepo} hasToken=${token ? 'yes' : 'no'} credId=${credentialsId ?: 'none'} repo=${or.owner}/${or.repo}"
  }

  if (!token) {
    // Fall back to agent’s native auth (deploy key, cached creds, etc.)
    int rc = sh(script: "git push origin ${tag}", returnStatus: true)
    if (rc != 0) {
      echo "release: push failed (status=${rc}) without token. Ensure Jenkins has push rights (deploy key with write or PAT)."
      error "release: git push failed (no token)"
    }
    return
  }

  // Preflight: can this token see the repo AND push?
  if (or.owner && or.repo) {
    def probe = ghProbeRepoAccess(token, or.owner, or.repo, apiBase)
    if (debug) echo "release: repo probe code=${probe.code} pushAllowed=${probe.push}"
    if (probe.code != '200') {
      error "release: token cannot see repo ${or.owner}/${or.repo} (HTTP ${probe.code}). Is the App installed on this repo or is PAT scoped correctly?"
    }
    if (!probe.push) {
      error "release: token lacks push permission on ${or.owner}/${or.repo}. Grant Contents: Read & write (App) or use a PAT with repo contents write. Check protected tags as well."
    }
  }

  // Use token for HTTPS push (GitHub App tokens and PATs):
  // username must be 'x-access-token' and password is the token.
  writeFile file: 'git-askpass.sh', text: '#!/bin/sh\necho "$GITHUB_TOKEN"\n'
  sh 'chmod 700 git-askpass.sh'
  def ask = "${pwd()}/git-askpass.sh"
  withEnv(["GIT_ASKPASS=${ask}", "GITHUB_TOKEN=${token}"]) {
    int rc = sh(script: """
       git remote set-url origin https://x-access-token@${httpsRepo.replaceFirst(/^https:\\/\\//,'')}
       GIT_CURL_VERBOSE=${debug ? 1 : 0} git push origin ${tag}
    """.stripIndent(), returnStatus: true)
    if (rc != 0) {
      echo "release: push failed (status=${rc}). Token may lack permissions or protected tags block creation."
      error "release: git push failed"
    }
  }
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
