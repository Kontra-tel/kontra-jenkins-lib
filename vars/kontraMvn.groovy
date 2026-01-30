/**
 * kontraMvn - Maven wrapper with optional Jenkins Config File Provider + Credentials binding.
 *
 * This is safe for a public shared lib:
 * - No secrets stored here.
 * - Only references Jenkins credential IDs / managed file IDs (which are not secrets).
 *
 * Defaults can be overridden by:
 *  1) arguments to this step
 *  2) environment variables
 *  3) hardcoded defaults below
 *
 * Required plugins if using settingsId:
 * - Config File Provider plugin
 *
 * Required credentials if using credsId:
 * - Username with password (username = GitHub user, password = PAT)
 */
def call(Map cfg = [:]) {
  // ---- What to run ----
  String goals = (cfg.goals ?: cfg.args ?: 'verify') as String

  // ---- Defaults (can be overridden per Jenkins instance/job) ----
  String mavenCmd   = (cfg.mavenCmd ?: env.KONTRA_MVN_CMD ?: 'mvn') as String
  String settingsId = (cfg.settingsId ?: env.KONTRA_MVN_SETTINGS_ID ?: '') as String   // e.g. 'maven-settings-github-packages'
  String credsId    = (cfg.credsId ?: env.KONTRA_MVN_CREDS_ID ?: '') as String         // e.g. 'github-packages'

  // ---- Credential variable names (override if your org uses different names) ----
  String userVar  = (cfg.usernameVar ?: env.KONTRA_MVN_USER_VAR ?: 'GITHUB_PACKAGES_USER') as String
  String tokenVar = (cfg.passwordVar ?: env.KONTRA_MVN_TOKEN_VAR ?: 'GITHUB_PACKAGES_TOKEN') as String

  // ---- Maven flags ----
  boolean batch    = (cfg.batch == null ? true : cfg.batch as boolean)
  boolean noTransferProgress = (cfg.ntp == null ? true : cfg.ntp as boolean)
  boolean updateSnapshots = (cfg.update == null ? true : cfg.update as boolean)

  List<String> flags = []
  if (batch) flags << '-B'
  if (noTransferProgress) flags << '-ntp'
  if (updateSnapshots) flags << '-U'
  if (cfg.extraFlags) flags.addAll(cfg.extraFlags as List<String>)

  // Optional: skip tests switch
  if (cfg.skipTests == true) {
    flags << '-DskipTests=true'
  }

  // Compose shell command (settings path injected later if used)
  def runMvn = { String settingsPathOrEmpty ->
    String settingsPart = settingsPathOrEmpty?.trim() ? "-s \"${settingsPathOrEmpty}\" " : ""
    String cmd = "${mavenCmd} ${settingsPart}${flags.join(' ')} ${goals}"
    sh cmd
  }

  // ---- Execution paths ----
  // 1) If settingsId specified -> use Config File Provider to inject settings.xml
  // 2) If credsId specified -> bind username/password as env vars (for settings.xml interpolation or other auth)
  // 3) If neither specified -> just run Maven
  if (settingsId?.trim()) {
    // configFileProvider step is available when Config File Provider plugin is installed
    configFileProvider([configFile(fileId: settingsId, variable: 'MAVEN_SETTINGS')]) {
      if (credsId?.trim()) {
        withCredentials([usernamePassword(credentialsId: credsId,
                                          usernameVariable: userVar,
                                          passwordVariable: tokenVar)]) {
          runMvn(env.MAVEN_SETTINGS)
        }
      } else {
        runMvn(env.MAVEN_SETTINGS)
      }
    }
  } else if (credsId?.trim()) {
    withCredentials([usernamePassword(credentialsId: credsId,
                                      usernameVariable: userVar,
                                      passwordVariable: tokenVar)]) {
      runMvn('')
    }
  } else {
    runMvn('')
  }
}
