def call(String goals) {
  call([goals: goals])
}

/**
 * kontraMvn - Maven wrapper with optional Jenkins Config File Provider + Credentials binding.
 *
 * Safe for public shared lib:
 * - No secrets stored here.
 * - Only references Jenkins credential IDs / managed file IDs (not secrets).
 */
def call(Map cfg = [:]) {
  String goals = (cfg.goals ?: cfg.args ?: 'verify') as String

  String mavenCmd   = (cfg.mavenCmd ?: env.KONTRA_MVN_CMD ?: 'mvn') as String
  String settingsId = (cfg.settingsId ?: env.KONTRA_MVN_SETTINGS_ID ?: '') as String
  String credsId    = (cfg.credsId ?: env.KONTRA_MVN_CREDS_ID ?: '') as String

  String userVar  = (cfg.usernameVar ?: env.KONTRA_MVN_USER_VAR ?: 'GITHUB_PACKAGES_USER') as String
  String tokenVar = (cfg.passwordVar ?: env.KONTRA_MVN_TOKEN_VAR ?: 'GITHUB_PACKAGES_TOKEN') as String

  boolean batch = (cfg.batch == null ? true : cfg.batch as boolean)
  boolean ntp   = (cfg.ntp == null ? true : cfg.ntp as boolean)
  boolean updateSnapshots = (cfg.update == null ? true : cfg.update as boolean)

  List<String> flags = []
  if (batch) flags << '-B'
  if (ntp) flags << '-ntp'
  if (updateSnapshots) flags << '-U'
  if (cfg.extraFlags) flags.addAll(cfg.extraFlags as List<String>)
  if (cfg.skipTests == true) flags << '-DskipTests=true'

  echo "kontraMvn: settingsId=${settingsId ?: '(none)'} credsId=${credsId ?: '(none)'} goals=${goals}"

  def runMvn = { String settingsPathOrEmpty ->
    String settingsPart = settingsPathOrEmpty?.trim() ? "-s \"${settingsPathOrEmpty}\" " : ""
    String cmd = "${mavenCmd} ${settingsPart}${flags.join(' ')} ${goals}"
    sh cmd
  }

  if (settingsId?.trim()) {
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
