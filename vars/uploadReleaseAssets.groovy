// vars/uploadReleaseAssets.groovy
// Upload assets to an existing GitHub Release identified by tag.
// Inputs:
// - tag (required)
// - credentialsId (required)
// - assets: String (comma-separated/glob) or List<String>
// - assetsRename: Map<sourcePath, newName>
// - assetOverwrite: boolean (delete existing asset with same name)
// - assetContentType: default 'application/octet-stream'
// - githubApi, githubUploads: override endpoints

def call(Map cfg = [:]) {
  final String tag              = (cfg.tag ?: '').toString().trim()
  final String credentialsId    = (cfg.credentialsId ?: '').toString().trim()
  if (!tag || !credentialsId) { echo 'uploadReleaseAssets: tag and credentialsId are required'; return }

  def assetsCfgRaw              = cfg.assets
  Map assetsRename              = (cfg.assetsRename instanceof Map) ? (Map)cfg.assetsRename : [:]
  final boolean assetOverwrite  = (cfg.assetOverwrite == true)
  final String assetContentType = (cfg.assetContentType ?: 'application/octet-stream') as String
  final String githubApi        = (cfg.githubApi ?: 'https://api.github.com') as String
  final String githubUploads    = (cfg.githubUploads ?: 'https://uploads.github.com') as String

  String origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
  Map or = detectOwnerRepo(origin)
  String owner = or.owner
  String repo  = or.repo
  if (!owner || !repo) { echo 'uploadReleaseAssets: cannot detect owner/repo'; return }

  String token = resolveGithubToken(credentialsId, owner)
  if (!token) { echo 'uploadReleaseAssets: no GitHub token'; return }

  String rid = getReleaseIdByTag(githubApi, owner, repo, tag, token)
  if (!rid) { echo "uploadReleaseAssets: no release id for tag ${tag}"; return }

  List<String> assetsList = normalizeAssetsList(assetsCfgRaw)
  List<String> files = expandGlobs(assetsList)
  if (files.isEmpty()) { echo "uploadReleaseAssets: no files matched ${assetsList}"; return }

  Map existing = listReleaseAssets(githubApi, owner, repo, rid, token)
  for (String f : files) {
    String name = assetsRename?.get(f) ?: new File(f).getName()
    if (assetOverwrite && existing.containsKey(name)) {
      deleteReleaseAsset(githubApi, owner, repo, existing[name], token)
    } else if (existing.containsKey(name)) {
      echo "uploadReleaseAssets: asset '${name}' exists; skipping (set assetOverwrite:true to replace)"
      continue
    }
    String ctype = detectContentType(f, assetContentType)
    boolean ok = uploadReleaseAsset(githubUploads, owner, repo, rid, f, name, token, ctype)
    if (!ok) echo "uploadReleaseAssets: failed to upload '${name}' from '${f}'"
  }
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
  try {
    if (ownerHint) return githubAppToken(credentialsId: credentialsId, owner: ownerHint)
    return githubAppToken(credentialsId: credentialsId)
  } catch (Throwable ignore) {}
  try {
    def token = null
    withCredentials([string(credentialsId: credentialsId, variable: 'GITHUB_TOKEN_TMP')]) { token = env.GITHUB_TOKEN_TMP }
    if (token) return token
  } catch (Throwable ignore) {}
  try {
    def tok = null
    withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER_TMP', passwordVariable: 'GITHUB_PASS_TMP')]) { tok = env.GITHUB_PASS_TMP }
    if (tok) return tok
  } catch (Throwable ignore) {}
  return null
}

private String getReleaseIdByTag(String apiBase, String owner, String repo, String tag, String token) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json'"
  String body = sh(script: "curl -sS ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/tags/${tag}", returnStdout: true).trim()
  def m = (body =~ /\"id\"\s*:\s*(\d+)/)
  return m.find() ? m.group(1) : ''
}

private Map listReleaseAssets(String apiBase, String owner, String repo, String rid, String token) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json'"
  String json = sh(script: "curl -sS ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/${rid}/assets", returnStdout: true).trim()
  Map out = [:]
  try {
    def items = new groovy.json.JsonSlurperClassic().parseText(json)
    items.each { it -> if (it?.name && it?.id) out[it.name.toString()] = it.id.toString() }
  } catch (Throwable ignore) {}
  return out
}

private void deleteReleaseAsset(String apiBase, String owner, String repo, String assetId, String token) {
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Accept: application/vnd.github+json'"
  sh "curl -sS -X DELETE ${hdrs} ${apiBase}/repos/${owner}/${repo}/releases/assets/${assetId} -o /dev/null"
}

private boolean uploadReleaseAsset(String uploadsBase, String owner, String repo, String rid, String filePath, String name, String token, String contentType) {
  String encName = java.net.URLEncoder.encode(name, 'UTF-8').replaceAll('\\\\+','%20')
  String hdrs = "-H 'Authorization: Bearer ${token}' -H 'Content-Type: ${contentType}'"
  return sh(script: "curl -sS --data-binary @\"${filePath}\" ${hdrs} ${uploadsBase}/repos/${owner}/${repo}/releases/${rid}/assets?name=${encName} -o /dev/null", returnStatus: true) == 0
}

private List<String> normalizeAssetsList(def raw) {
  if (raw == null) return []
  if (raw instanceof List) return raw.collect { it.toString() }
  String s = raw.toString()
  if (!s) return []
  return s.split(',').collect { it.trim() }.findAll { it }
}

private List<String> expandGlobs(List<String> patterns) {
  Set<String> out = [] as Set
  for (String p : patterns) {
    String cmd = "ls -1 ${p} 2>/dev/null || true"
    String res = sh(script: cmd, returnStdout: true).trim()
    if (res) {
      res.split(/\r?\n/).each { out << it.trim() }
    }
  }
  return out as List<String>
}

private String detectContentType(String path, String fallback) {
  String n = path.toLowerCase()
  if (n.endsWith('.jar')) return 'application/java-archive'
  if (n.endsWith('.zip')) return 'application/zip'
  if (n.endsWith('.tar.gz') || n.endsWith('.tgz')) return 'application/gzip'
  if (n.endsWith('.gz')) return 'application/gzip'
  if (n.endsWith('.tar')) return 'application/x-tar'
  if (n.endsWith('.json')) return 'application/json'
  if (n.endsWith('.md')) return 'text/markdown'
  if (n.endsWith('.txt')) return 'text/plain'
  return fallback ?: 'application/octet-stream'
}
