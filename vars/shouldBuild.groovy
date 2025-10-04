// vars/shouldBuild.groovy
//
// Determines if a build should proceed based on commit message tokens.
// Useful for token-driven workflows where builds should only run when
// specific tokens are present in the commit message.
//
// Returns: true if build should proceed, false otherwise
//
// Usage:
//   stage('Check') {
//     steps {
//       script {
//         if (!shouldBuild(requiredTokens: ['!tag', '!release'])) {
//           currentBuild.result = 'NOT_BUILT'
//           error('No required tokens found. Build skipped.')
//         }
//       }
//     }
//   }
//
def call(Map cfg = [:]) {
  // Config
  List<String> requiredTokens = cfg.requiredTokens ?: ['!tag', '!release']
  boolean force = cfg.force == true || env.FORCE_BUILD == 'true'
  boolean anyToken = cfg.anyToken == true  // If true, match ANY token; if false (default), match ALL tokens
  boolean verbose = cfg.verbose != false   // Default to verbose logging

  // Fetch commit message
  String commitMsg = ''
  if (env.COMMIT_MESSAGE) {
    commitMsg = env.COMMIT_MESSAGE
  } else {
    try {
      commitMsg = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
      env.COMMIT_MESSAGE = commitMsg
    } catch (Exception e) {
      echo "shouldBuild: Failed to fetch commit message: ${e.message}"
      if (force) {
        echo "shouldBuild: Force build enabled, proceeding anyway"
        return true
      }
      return false
    }
  }

  if (verbose) {
    echo "shouldBuild: Checking commit message for required tokens"
    echo "shouldBuild: Commit message: ${commitMsg}"
    echo "shouldBuild: Required tokens: ${requiredTokens}"
    echo "shouldBuild: Match mode: ${anyToken ? 'ANY' : 'ALL'}"
  }

  // Force build overrides token check
  if (force) {
    echo "shouldBuild: Force build enabled, skipping token check"
    return true
  }

  // Check for tokens
  boolean shouldProceed
  if (anyToken) {
    // Match ANY token (at least one must be present)
    shouldProceed = requiredTokens.any { token -> commitMsg.contains(token) }
  } else {
    // Match ALL tokens (all must be present)
    shouldProceed = requiredTokens.every { token -> commitMsg.contains(token) }
  }

  if (shouldProceed) {
    if (verbose) {
      def matched = requiredTokens.findAll { token -> commitMsg.contains(token) }
      echo "shouldBuild: ✓ Build should proceed (matched tokens: ${matched})"
    }
    return true
  } else {
    echo "shouldBuild: ✗ No required tokens found in commit message"
    echo "shouldBuild: Build will be skipped"
    echo "shouldBuild: Hint: Add one of these tokens to your commit message: ${requiredTokens}"
    return false
  }
}
