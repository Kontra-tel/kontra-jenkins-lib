def call() {
  echo "=== Kontra CI Bootstrap ==="

  try {
    def libs = currentBuild.rawBuild.getAction(
      org.jenkinsci.plugins.workflow.libs.LibraryRecordAction
    )?.libraries

    libs?.each { lib ->
      if (lib.name == 'kontra-jenkins-lib') {
        echo "Shared lib : ${lib.name}"
        echo "Version    : ${lib.version}"
        echo "Commit     : ${lib.revision}"
      }
    }
  } catch (ignored) {
    echo "Shared lib metadata unavailable"
  }

  echo "Job        : ${env.JOB_NAME}"
  echo "Build      : #${env.BUILD_NUMBER}"
  echo "Agent      : ${env.NODE_NAME}"
  echo "================================"
}
