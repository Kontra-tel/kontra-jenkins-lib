// vars/echoVersion.groovy
//
// Prints and returns the current version for the repository using this library.
// Resolution order:
//   1) env.BUILD_VERSION (sanitized)
//   2) version.txt (sanitized)
//   3) nearest tag matching v[0-9]* (sanitized)
//   4) fallback '0.0.0'
//
// Usage:
//   def v = echoVersion()                  // prints "Version: X.Y.Z" and returns it
//   def v = echoVersion(silent: true)      // returns it without printing
//
def call(Map cfg = [:]) {
  final boolean silent = (cfg.silent == true)
  final String  tagPattern = (cfg.tagPattern ?: 'v[0-9]*') as String

  String v = null
  try {
    if (env.BUILD_VERSION?.toString()?.trim()) {
      v = sanitizeSemver(env.BUILD_VERSION.toString().trim())
    }
  } catch (Throwable ignore) {}

  if (!v || v == '0.0.0') {
    try {
      if (fileExists('version.txt')) {
        String f = readFile(file: 'version.txt').trim()
        v = sanitizeSemver(f)
      }
    } catch (Throwable ignore) {}
  }

  if (!v || v == '0.0.0') {
    try {
      String t = sh(script: "git describe --tags --abbrev=0 --match '${tagPattern}' 2>/dev/null || true", returnStdout: true).trim()
      v = sanitizeSemver(t)
    } catch (Throwable ignore) {}
  }

  if (!v) v = '0.0.0'
  if (!silent) echo "Version: ${v}"
  return v
}

// ---- helpers ----
private String sanitizeSemver(String v) {
  if (!v) return '0.0.0'
  // Extract first X.Y.Z occurrence even if prefixed by characters like 'v' or suffixed with pre-release/build metadata
  def m = (v =~ /(\d+)\.(\d+)\.(\d+)/)
  return m.find() ? m.group(0) : '0.0.0'
}
