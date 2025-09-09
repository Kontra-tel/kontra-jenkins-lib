// vars/release.groovy
//
// Creates/pushes a git tag (e.g., vX.Y.Z) and optionally a GitHub Release.
// Expects `version:` (string). Does NOT compute/bump versions.
//
// Tag gates:
//   - alwaysTag:true                     -> tag every time
//   - tagOnRelease:true & '!release' in commit OR forceRelease:true
//   - onlyTagOnMain:true                 -> only tag on mainBranch
//
// GitHub:
//   - Use `credentialsId` (GitHub App or PAT) to push tags / create releases.
//   - Set createGithubRelease:true to create/update a release.
//   - Set notesSinceLastTag:true (default) to include notes since previous tag.
//   - Optional: fallbackUseReleaseToCreateTag:true lets Releases API create the tag if push & Git API both fail.
//
// Returns: [tag, tagged, pushed, githubReleased, isRelease, branch]
//
def call(Map cfg = [:]) {
  String version = (cfg.version ?: env.BUILD_VERSION ?: '').toString().trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  // Core config
  final String  tagPrefix     = (cfg.tagPrefix ?: 'v') as String
  final String  releaseToken  = (cfg.releaseToken ?: '!release') as String
  final boolean tagOnRelease  = (cfg.tagOnRelease == false) ? false : true
  final boolean forceRelease  = (cfg.forceRelease == true || env.FORCE_RELEASE == 'true')
  final boolean onlyTagOnMain = (cfg.onlyTagOnMain == false) ? false : true
  final String  mainBranch    = (cfg.mainBranch ?: cfg.releaseBranch ?: 'main') as String
  final boolean alwaysTag     = (cfg.alwaysTag == true)

  // Auth / behavior
  final boolean pushTags      = (cfg.pushTags == false) ? false : true
  final String  credentialsId = (cfg.credentialsId ?: null) as String
  final String  ownerHint     = (cfg.owner ?: cfg.ownerHint ?: null) as String // accept both keys
  final String  gitUserName   = (cfg.gitUserName ?: 'Jenkins CI') as String
  final String  gitUserEmail  = (cfg.gitUserEmail ?: 'jenkins@local') as String
  final boolean debug         = (cfg.debug == true || (binding.hasVariable('params') && params.DEBUG_RELEASE == true))

  // GitHub Release settings
  final boolean createGithubRelease  = (cfg.createGithubRelease == true)
  final boolean releaseDraft         = (cfg.releaseDraft == true)
  final boolean prerelease           = (cfg.prerelease == true)
  final boolean generateNotesFlag    = (cfg.generateReleaseNotes == false) ? false : true
  final String  githubApi            = (cfg.githubApi ?: 'https://api.github.com') as String

  // Notes generation
  final boolean notesSinceLastTag    = (cfg.notesSinceLastTag == false) ? false : true
  final boolean includeMergeCommits  = (cfg.notesIncludeMerges == true)
  final int     notesMaxCommits      = (cfg.notesMaxCommits ?: 200) as int
  final boolean addCompareLink       = (cfg.addCompareLink == false) ? false : true

  // Optional last-resort: use Releases API to create tag if other methods fail
  final boolean fallbackUseReleaseToCreateTag = (cfg.fallbackUseReleaseToCreateTag == true)

  // Make sure local sees remote tags (for prev tag detection)
  sh "git fetch --tags --force --prune || true"

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

  // Should we tag now?
  boolean isRelease = forceRelease || (tagOnRelease && commitMsg.contains(releaseToken))
  boolean allowedBr = !onlyTagOnMain || (branch == mainBranch)
  boolean shouldTag = allowedBr && (alwaysTag || isRelease)

  boolean tagged = false
  boolean pushed = false
  boolean ghRel  = false

  if (!shouldTag) {
    echo "release: gated off → branch=${branch} onlyTagOnMain=${onlyTagOnMain} alwaysTag=${alwaysTag} isRelease=${isRelease}"
    return [tag: tag, tagged: false, pushed: false, githubReleased: false, isRelease: isRelease, branch: branch]
  }

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
    pushed = pushOrCreateTag(
      tag, credentialsId, ownerHint, githubApi,
      gitUserName, gitUserEmail, debug, fallbackUseReleaseToCreateTag, createGithubRelease
    )
  }

  // ----- GitHub Release (with notes) -----
  String notesBody = null
  if (createGithubRelease && credentialsId) {
    if (notesSinceLastTag) {
      def or = detectOwnerRepo(sh(script: 'git config --get remote.origin.url', returnStdout: true).trim())
      String prev = previousTag(tag, tagPrefix)
      notesBody = buildNotesSince(prev, tag, includeMergeCommits, notesMaxCommits, or.owner, or.repo, addCompareLink)
    }
    ghRel = createOrUpdateRelease(tag, credentialsId, githubApi, releaseDraft, prerelease,
                                  notesBody ? false : generateNotesFlag, // if we provide body, don't ask GH to generate
                                  notesBody)
  }

  return [tag: tag, tagged: tagged, pushed: pushed, githubReleased: ghRel, isRelease: isRelease, branch: branch]
}

// ---------- helpers ----------

private boolean tagAlreadyExists(String tag) {
  return (sh(script: "git rev-parse -q --verify refs/tags/${tag}", returnStatus: true) == 0)
}

private Map detectOwnerRepo(String originUrl) {
  String url = originUrl ?: ''
  url = url.replaceAll(/\\.git$/, '')
  if (url.startsWith('git@github.com:')) {
    url = 'https://github.com/' + url.substring('git@github.com:'.length())
  }
  def m = (url =~ /github\\.com\\/([^\\/]+)\\/([^\\/]+)$/)
  if (m.find()) return [owner: m.group(1), repo: m.group(2)]
  return [owner: '', repo: '']
}

private String resolveGithubToken(String credentialsId, String ownerHint) {
  if (!credentialsId) return null
  // GitHub App (via Branch Source)
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

private boolean pushOrCreateTag(String tag,
                                String credentialsId,
                                String ownerHint,
                                String apiBase,
                                String gitUserName,
                                String gitUserEmail,
                                boolean debug,
                                boolean fallbackUseReleaseToCreateTag,
                                boolean createGithubReleaseFlag) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String httpsRepo = origin
    .replaceFirst(/^git@github\\.com:/, 'https://github.com/')
    .replaceFirst(/^https:\\/\\/[^@]+@github\\.com\\//, 'https://github.com/')

  String token = resolveGithubToken(credentialsId, ownerHint ?: or.owner)
  String tokInfo = token ? (token.startsWith('gh') ? token.substring(0, token.indexOf('_') > 0 ? token.indexOf('_')+1 : Math.min(6, token.length())) + '…' : '***') : 'none'

  if (debug) {
    echo "release: pushTag repo=${or.owner}/${or.repo} credId=${credentialsId ?: 'none'} token=${tokInfo}"
  }

  // Remote already has the tag? We're done.
  if (remoteTagExistsAPI(or.owner, or.repo, token, apiBase, tag)) {
    if (debug) echo "release: remote tag ${tag} already exists; skipping push"
    return true
  }

  // Try plain git push (works with PATs, deploy keys, cached credentials)
  if (!token) {
    int rc0 = sh(script: "git push origin ${tag}", returnStatus: true)
    if (rc0 == 0) return true
    echo "release: push failed (status=${rc0}) without token. Configure a PAT or App with write + ruleset bypass."
    error "release: git push failed (no token)"
  }

  // Try git push with token embedded
  String hostPath = httpsRepo.replaceFirst(/^https:\\/\\//, '')
  int rc = sh(script: """
    set -e
    git remote set-url origin https://x-access-token:${token}@${hostPath}
    GIT_CURL_VERBOSE=${debug ? 1 : 0} git push origin ${tag}
  """.stripIndent(), returnStatus: true)
  if (rc == 0) return true

  // --- REST fallbacks ---

  // 1) Lightweight tag (ref → commit)
  String sha = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  if (tryCreateRef(apiBase, or.owner, or.repo, token, "refs/tags/${tag}", sha, debug)) {
    return true
  }

  // 2) Annotated tag (create tag object, then ref → tag object)
  if (tryCreateAnnotatedTag(apiBase, or.owner, or.repo, token, tag, sha, gitUserName, gitUserEmail, debug)) {
    return true
  }

  // 3) Optional last resort: let Releases API create the tag
  if (fallbackUseReleaseToCreateTag && createGithubReleaseFlag) {
    if (tryCreateTagViaRelease(apiBase, or.owner, or.repo, token, tag, sha, debug)) {
      return true
    }
  }

  echo "release: all tag creation methods failed (git push, lightweight ref, annotated tag${fallbackUseReleaseToCreateTag ? ', release' : ''})."
  echo "release: likely causes → App lacks 'Repository contents: Read & write' OR tag ruleset blocks this actor."
  error "release: tag creation failed"
}

private boolean tryCreateRef(String apiBase, String owner, String repo, String token, String ref, String sha, boolean debug) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"
  String payload = """{"ref":"${ref}","sha":"${sha}"}"""
  String code = sh(script: "curl -s -o /dev/null -w '%{http_code}' -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/refs -d '${payload}'", returnStdout: true).trim()
  if (code == '201' || code == '200' || code == '422') return true // 422 -> already exists
  if (debug) {
    String body = sh(script: "curl -s -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/refs -d '${payload}'", returnStdout: true).trim()
    echo "release: createRef ${ref} → HTTP ${code}: ${body.take(180)}"
  }
  return false
}

private boolean tryCreateAnnotatedTag(String apiBase, String owner, String repo, String token, String tag, String commitSha, String name, String email, boolean debug) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"
  String iso = sh(script: "date -u +%Y-%m-%dT%H:%M:%SZ", returnStdout: true).trim()

  // Create tag object
  String payloadTag = """{"tag":"${tag}","message":"Release ${tag}","object":"${commitSha}","type":"commit","tagger":{"name":"${name}","email":"${email}","date":"${iso}"}}"""
  String createTagJson = sh(script: "curl -s -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/tags -d '${payloadTag}'", returnStdout: true).trim()
  String tagObjSha = sh(script: "printf %s '${createTagJson}' | sed -n 's/.*\"sha\"\\s*:\\s*\"\\([0-9a-f]\\{40\\}\\)\".*/\\1/p' | head -n1", returnStdout: true).trim()

  if (!tagObjSha) {
    if (debug) echo "release: createTagObject failed: ${createTagJson.take(180)}"
    return false
  }

  // Point ref to tag object
  String payloadRef = """{"ref":"refs/tags/${tag}","sha":"${tagObjSha}"}"""
  String refCode = sh(script: "curl -s -o /dev/null -w '%{http_code}' -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/refs -d '${payloadRef}'", returnStdout: true).trim()
  if (refCode == '201' || refCode == '200' || refCode == '422') return true

  if (debug) {
    String body = sh(script: "curl -s -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/git/refs -d '${payloadRef}'", returnStdout: true).trim()
    echo "release: createRef (annotated) → HTTP ${refCode}: ${body.take(180)}"
  }
  return false
}

private boolean tryCreateTagViaRelease(String apiBase, String owner, String repo, String token, String tag, String commitSha, boolean debug) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json' -H 'Content-Type: application/json'"
  String payload = """{"tag_name":"${tag}","target_commitish":"${commitSha}","name":"${tag}","draft":true,"prerelease":false,"generate_release_notes":false}"""
  String code = sh(script: "curl -s -o /dev/null -w '%{http_code}' -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases -d '${payload}'", returnStdout: true).trim()
  if (code == '201' || code == '200') return true
  if (debug) {
    String body = sh(script: "curl -s -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases -d '${payload}'", returnStdout: true).trim()
    echo "release: createTagViaRelease → HTTP ${code}: ${body.take(180)}"
  }
  return false
}

// --------- Release creation/update with notes ---------

private boolean createOrUpdateRelease(String tag,
                                      String credentialsId,
                                      String apiBase,
                                      boolean draft,
                                      boolean prerelease,
                                      boolean generateNotes,
                                      String bodyText) {
  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String owner = or.owner
  String repo  = or.repo
  if (!owner || !repo) { echo "release: owner/repo not detected; skipping GitHub Release"; return false }

  String token = resolveGithubToken(credentialsId, owner)
  if (!token) { echo "release: no GitHub token; skipping GitHub Release"; return false }

  String hdrs = "-H \"Authorization: Bearer ${token}\" -H \"Accept: application/vnd.github+json\" -H \"Content-Type: application/json\""
  String status = sh(script: "curl -s -o /dev/null -w '%{http_code}' ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()

  // Build payload fragments
  String bodyField = bodyText?.trim() ? "\"body\":${toJson(bodyText)}" : null
  String genNotes  = (!bodyField && generateNotes) ? "\"generate_release_notes\":true" : null

  if (status == '200') {
    String rid = sh(script: "curl -s ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag} | sed -n 's/.*\"id\"\\s*:\\s*\\([0-9][0-9]*\\).*/\\1/p' | head -n1", returnStdout: true).trim()
    if (rid) {
      String payload = "{ \"name\":\"${tag}\", \"draft\":${draft}, \"prerelease\":${prerelease}" +
                       (bodyField ? ", ${bodyField}" : "") + " }"
      sh "curl -sS -X PATCH ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/${rid} -d '${payload}' >/dev/null"
      return true
    }
  }

  String createPayload = "{ \"tag_name\":\"${tag}\", \"name\":\"${tag}\", \"draft\":${draft}, \"prerelease\":${prerelease}" +
                         (bodyField ? ", ${bodyField}" : (genNotes ? ", ${genNotes}" : "")) + " }"
  sh "curl -sS -X POST ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases -d '${createPayload}' >/dev/null"
  return true
}

// Find the previous tag (by version sort) with the same prefix, excluding current.
private String previousTag(String currentTag, String tagPrefix) {
  String pattern = tagPrefix.replaceAll('([\\\\.^$|?*+()\\[\\]])', '\\\\$1') + '[0-9]*'
  String list = sh(script: "git -c versionsort.suffix=- tag -l '${pattern}' --sort=-v:refname || true", returnStdout: true).trim()
  if (!list) return ''
  def tags = list.readLines().findAll { it?.trim() && it.trim() != currentTag }
  return tags ? tags[0].trim() : ''
}

// Build markdown notes since previous tag (or all history if none).
private String buildNotesSince(String prevTag,
                               String currTag,
                               boolean includeMerges,
                               int maxCommits,
                               String owner,
                               String repo,
                               boolean addCompareLink) {
  String range = prevTag ? "${prevTag}..${currTag}" : currTag
  String fmt   = "--pretty=%H%x1f%an%x1f%s%x1e"
  String opt   = includeMerges ? "" : "--no-merges"
  String raw   = sh(script: "git log ${opt} --max-count=${maxCommits} ${fmt} ${range} || true", returnStdout: true).trim()

  def out = new StringBuilder()
  if (prevTag) {
    out << "### Changes since `${prevTag}`\n\n"
  } else {
    out << "### Changes\n\n"
  }

  if (raw) {
    raw.split('\\u001e').each { rec ->
      rec = rec?.trim()
      if (!rec) return
      def f = rec.split('\\u001f')
      if (f.size() >= 3) {
        String sha = f[0]
        String who = f[1]
        String sub = f[2]
        String shortSha = sha.take(7)
        out << "- ${sub} (${who}, ${shortSha})\n"
      }
    }
  } else {
    out << "_No changes found in range._\n"
  }

  if (addCompareLink && owner && repo && prevTag) {
    out << "\n[Compare ${prevTag}...${currTag}](https://github.com/${owner}/${repo}/compare/${prevTag}...${currTag})\n"
  }
  return out.toString()
}

private String toJson(String s) {
  if (s == null) return "null"
  return '"' + s
    .replace('\\', '\\\\')
    .replace('"', '\\"')
    .replace('\r', '\\r')
    .replace('\n', '\\n') + '"'
}
