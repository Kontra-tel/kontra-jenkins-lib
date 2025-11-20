// vars/release.groovy
//
// Creates/pushes a git tag (e.g. vX.Y.Z) and optionally a GitHub Release.
// Expects `version:` (string). Does NOT co  if (ghReleaseRequested && credentialsId) {
    // If we attempted to push the tag, trust that it succeeded
    // (the git push command would have failed if it didn't work)
    if (!pushed && !remoteTagExists(tag)) {
      echo "release: GH release requested but tag ${tag} not found on origin and push was not attempted"
      echo "release: Enable pushTags or ensure the tag exists on remote first"
      if (debug) {
        echo "release: ghReleaseRequested=${ghReleaseRequested}, credentialsId=${credentialsId}"
        echo "release: tagged=${tagged}, pushed=${pushed}"
      }
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
    
    if (debug) echo "release: Tag ${tag} should be on remote (pushed=${pushed}), proceeding with GitHub Release creation"
//
// Tagging gates (independent of GH Release):
//   - alwaysTag:true                      -> tag every build
//   - tagOnRelease:true (default) & commit has '!tag' OR forceRelease:true
//   - onlyTagOnMain:true (default)        -> allow tagging only on releaseBranch
//
// GitHub Release will be created when ALL are true:
//   - credentialsId provided (GitHub App or PAT) AND
//   - not explicitly suppressed by '!no-ghrelease' AND
//   - any of:
//       * cfg.createGithubRelease == true
//       * commit message contains '!release' (or !ghrelease, !github-release)
//       * cfg.forceGithubRelease == true  OR env.FORCE_GH_RELEASE == 'true'
//
// Additionally, if a GH Release is requested, this step ensures the tag exists
// (respecting branch gate) and pushes it before creating/updating the release.
//
// Token Summary:
//   !tag           -> Create and push git tag
//   !release       -> Create GitHub Release (requires tag to exist on remote)
//   !no-ghrelease  -> Suppress GitHub Release creation
//
// Returns: [tag, tagged, pushed, githubReleased, isRelease, ghReleaseRequested, branch]
//
def call(Map cfg = [:]) {
  // Required
  String version = (cfg.version ?: env.BUILD_VERSION ?: '').toString().trim()
  if (!version) error "release: 'version' is required (or set env.BUILD_VERSION)"

  // Core config (provide safe defaults for all referenced symbols)
  final String  tagPrefix          = (cfg.tagPrefix ?: 'v') as String
  final String  tagPattern         = (cfg.tagPattern ?: 'v[0-9]*') as String
  final boolean tagOnRelease       = (cfg.tagOnRelease == false) ? false : true
  final boolean forceRelease       = (cfg.forceRelease == true)
  final boolean onlyTagOnMain      = (cfg.onlyTagOnMain == false) ? false : true
  final String  mainBranch         = (cfg.releaseBranch ?: cfg.mainBranch ?: 'main') as String
  final boolean alwaysTag          = (cfg.alwaysTag == true)

  // Tokens (simplified)
  // Tagging triggered by '!tag' only
  // GitHub Release triggered by '!release' (and aliases for compatibility)
  final List<String> releaseTokens     = (cfg.releaseTokens instanceof List ? cfg.releaseTokens : null) ?: ['!tag']
  final List<String> ghReleaseTokens   = (cfg.ghReleaseTokens instanceof List ? cfg.ghReleaseTokens : null) ?: ['!release', '!ghrelease', '!github-release']
  final List<String> ghReleaseNoTokens = (cfg.ghReleaseNoTokens instanceof List ? cfg.ghReleaseNoTokens : null) ?: ['!no-ghrelease', '!no-github-release']
  final String       ghReleaseToken    = (cfg.ghReleaseToken ?: '!release') as String  // primary token for GitHub Release
  final String       ghReleaseNoToken  = (cfg.noGhReleaseToken ?: '!no-ghrelease') as String
  final boolean      forceGithubRelease= (cfg.forceGithubRelease == true) || (cfg.createGithubRelease == true) || (env.FORCE_GH_RELEASE == 'true')

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
  final boolean useChangelogModule  = (cfg.useChangelogModule == true)     // use generateChangelog for enhanced notes
  final String  notesHeader         = (cfg.releaseNotesHeader ?: 'Changes since last release:') as String
  final String  githubApi           = (cfg.githubApi ?: 'https://api.github.com') as String
  final String  githubUploads       = (cfg.githubUploads ?: 'https://uploads.github.com') as String
  // Optional asset upload configuration (delegated to uploadReleaseAssets step)
  def          assetsCfgRaw         = cfg.assets
  final boolean assetOverwrite      = (cfg.assetOverwrite == true)
  final String  assetContentType    = (cfg.assetContentType ?: 'application/octet-stream') as String
  Map           assetsRename        = (cfg.assetsRename instanceof Map) ? (Map)cfg.assetsRename : [:]

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

  // Decide if we should tag (via !tag token or alwaysTag)
  boolean tokenWantsTag = false
  for (String t : releaseTokens) { 
    if (commitMsg.contains(t)) { 
      tokenWantsTag = true
      break 
    }
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

  if (debug) {
    echo "release: GitHub Release decision:"
    echo "  - wantsGhByToken (commit has !release): ${wantsGhByToken}"
    echo "  - forceGithubRelease (param/config): ${forceGithubRelease}"
    echo "  - noGhByToken (commit has !no-ghrelease): ${noGhByToken}"
    echo "  - ghReleaseRequested (final): ${ghReleaseRequested}"
    echo "  - credentialsId: ${credentialsId}"
  }

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
      try {
        pushTag(tag, credentialsId, ownerHint, githubApi, debug)
        pushed = true
      } catch (Exception e) {
        String msg = e.message ?: ''
        boolean alreadyExists = msg.contains('already exists') || msg.contains('non-fast-forward')
        if (alreadyExists) {
          echo "release: WARNING: tag ${tag} already exists on remote; ignoring non-fast-forward push failure"
          pushed = false
        } else {
          throw e
        }
      }
    }
  } else {
    echo "release: tag creation gated off → branch=${branch} onlyTagOnMain=${onlyTagOnMain} alwaysTag=${alwaysTag} isRelease=${isRelease}"
  }

  if (ghReleaseRequested && credentialsId) {
    // If we attempted to push the tag, trust that it succeeded
    // (the git push command would have failed if it didn't work)
    if (!pushed && !remoteTagExists(tag)) {
      echo "release: GH release requested but tag ${tag} not found on origin and push was not attempted"
      echo "release: Enable pushTags or ensure the tag exists on remote first"
      if (debug) {
        echo "release: ghReleaseRequested=${ghReleaseRequested}, credentialsId=${credentialsId}"
        echo "release: tagged=${tagged}, pushed=${pushed}"
      }
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
    
    if (debug) echo "release: Tag ${tag} should be on remote (pushed=${pushed}), proceeding with GitHub Release creation"
    
    ghRel = createOrUpdateRelease(
      tag, credentialsId, githubApi,
      releaseDraft, prerelease,
      generateNotesFlag, attachCommitNotes, notesHeader, tagPrefix,
      useChangelogModule, version, debug, tagPattern
    )

    // Upload assets if configured (delegate to separate step)
    if (ghRel && assetsCfgRaw) {
      uploadReleaseAssets(
        tag: tag,
        credentialsId: credentialsId,
        assets: assetsCfgRaw,
        assetsRename: assetsRename,
        assetOverwrite: assetOverwrite,
        assetContentType: assetContentType,
        githubApi: githubApi,
        githubUploads: githubUploads
      )
    }
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
  
  // For GitHub App credentials, try multiple binding approaches
  // GitHub Apps store tokens differently depending on the plugin version
  
  // Method 1: Try gitHubApp credential binding (GitHub App plugin)
  try {
    String token = null
    // GitHub App credentials can be bound as a map with 'token' key
    withCredentials([[$class: 'org.jenkinsci.plugins.github_branch_source.GitHubAppCredentials', 
                      credentialsId: credentialsId, 
                      variable: 'GITHUB_APP']]) {
      token = env.GITHUB_APP
    }
    if (token) {
      echo "release: Successfully retrieved GitHub App token using GitHubAppCredentials binding"
      return token
    }
  } catch (Throwable e1) {
    // This is expected if GitHubAppCredentials binding is not available
  }
  
  // Method 2: Try usernamePassword binding (GitHub App credentials often work this way)
  try {
    String token = null
    withCredentials([usernamePassword(credentialsId: credentialsId, 
                                       usernameVariable: 'GH_APP_ID', 
                                       passwordVariable: 'GH_TOKEN')]) {
      token = env.GH_TOKEN
    }
    if (token) {
      echo "release: Successfully retrieved token using usernamePassword binding (GitHub App)"
      return token
    }
  } catch (Throwable e2) {
    echo "release: usernamePassword binding failed: ${e2.message}"
  }
  
  // Method 3: Fallback to string binding for PAT (Personal Access Token)
  try {
    String token = null
    withCredentials([string(credentialsId: credentialsId, variable: 'GITHUB_TOKEN')]) {
      token = env.GITHUB_TOKEN
    }
    if (token) {
      echo "release: Successfully retrieved token using string binding (PAT)"
      return token
    }
  } catch (Throwable e3) {
    echo "release: String credentials binding failed: ${e3.message}"
  }
  
  echo "release: Failed to retrieve token using any method for credentialsId: ${credentialsId}"
  return null
}

private void pushTag(String tag, String credentialsId, String ownerHint, String githubApi, boolean debug) {
  if (debug) echo "release: pushing tag ${tag} to origin"
  if (credentialsId) {
    // Get the appropriate token for the credential type
    String token = resolveGithubToken(credentialsId, ownerHint)
    if (token) {
      if (debug) echo "release: using token authentication for git push"
      
      // Get current remote URL 
      String originalUrl = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
      
      // Create authenticated URL
      String repoPath = ""
      if (originalUrl.startsWith('git@github.com:')) {
        repoPath = originalUrl.substring('git@github.com:'.length()).replaceAll(/\.git$/, '')
      } else if (originalUrl.startsWith('https://github.com/')) {
        repoPath = originalUrl.substring('https://github.com/'.length()).replaceAll(/\.git$/, '')
      } else {
        // Unknown URL format, try direct push
        if (debug) echo "release: unknown URL format, falling back to direct push"
        sh "git push origin ${tag}"
        return
      }
      
      String authUrl = "https://x-access-token:${token}@github.com/${repoPath}.git"
      if (debug) echo "release: using authenticated URL for push"
      
      try {
        // Use the authenticated URL directly for this push
        sh "git push '${authUrl}' ${tag}"
      } catch (Exception e) {
        if (debug) echo "release: authenticated push failed, trying fallback: ${e.message}"
        // Fallback to direct push
        sh "git push origin ${tag}"
      }
    } else {
      if (debug) echo "release: no token available, using default git authentication"
      sh "git push origin ${tag}"
    }
  } else {
    if (debug) echo "release: no credentials configured, using default git authentication"
    sh "git push origin ${tag}"
  }
}

private boolean createOrUpdateRelease(
  String tag, String credentialsId, String apiBase,
  boolean draft, boolean prerelease,
  boolean generateNotesFlag, boolean attachCommitNotes, String notesHeader, String tagPrefix,
  boolean useChangelogModule, String version, boolean debug, String tagPattern
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
  
  String body = ""
  if (attachCommitNotes) {
    if (useChangelogModule) {
      // Use generateChangelog for enhanced notes with grouping, full messages, and token removal
      if (debug) echo "release: Using generateChangelog module for release notes"
      
      // Generate changelog to a temporary file
      String tempChangelog = ".release-notes-${version}.md"
      try {
        generateChangelog([
          version: version,
          outputFile: tempChangelog,
          title: '',  // No title, we'll add our own header
          since: prevTag ?: '',
          tagPattern: "${tagPattern}[0-9]*"
        ])
        
        // Read the generated content (skip the version header, we'll add our own)
        String changelogContent = readFile(file: tempChangelog).trim()
        
        // Extract just the content without the ## version line
        def lines = changelogContent.split('\n')
        def contentLines = []
        boolean skipNext = false
        for (int i = 0; i < lines.length; i++) {
          if (lines[i].startsWith('## ')) {
            skipNext = true
            continue
          }
          if (skipNext && lines[i].trim() == '') {
            skipNext = false
            continue
          }
          contentLines << lines[i]
        }
        
        String header = prevTag ? "${notesHeader} (${prevTag} → ${tag})" : "${notesHeader}"
        body = header + "\n\n" + (contentLines ? contentLines.join('\n').trim() : "- (no user-visible changes)")
        
        // Clean up temp file
        sh "rm -f ${tempChangelog}"
      } catch (Exception e) {
        echo "release: Failed to generate changelog, falling back to simple commit list: ${e.message}"
        // Fall back to simple commit list
        String changes = commitRange ? sh(
          script: "git log --no-merges --pretty='- %s (%h)' ${commitRange}",
          returnStdout: true
        ).trim() : ""
        String header = prevTag ? "${notesHeader} (${prevTag} → ${tag})" : "${notesHeader}"
        body = header + "\n\n" + (changes ? changes : "- (no user-visible changes)")
      }
    } else {
      // Original simple commit list
      String changes = commitRange ? sh(
        script: "git log --no-merges --pretty='- %s (%h)' ${commitRange}",
        returnStdout: true
      ).trim() : ""
      String header = prevTag ? "${notesHeader} (${prevTag} → ${tag})" : "${notesHeader}"
      body = header + "\n\n" + (changes ? changes : "- (no user-visible changes)")
    }
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
