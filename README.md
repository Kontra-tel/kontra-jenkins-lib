# Kontra Jenkins Shared Library

Reusable Jenkins Pipeline steps for versioning, releasing, environment files, changelog generation, and systemd deployment.

## Provided Steps

| Step | Purpose |
|------|---------|
| `semver` | Compute the next semantic version (with forced bumps, hybrid tag/file baseline, skip-on-same-commit). Exports `env.BUILD_VERSION`. |
| `release` | Create/push a git tag and optionally create/update a GitHub Release. |
| `generateChangelog` | Generate or append a changelog section from recent commits. |
| `writeEnvFile` | Write a sanitized .env-style file from key/value pairs (supports dryRun). |
| `deploySystemd` | Deploy a service unit (generic; supports repo `launch.sh` or custom command; supports dryRun). |

## Install as a Global Library

1. In Jenkins, Manage Jenkins → Global Pipeline Libraries → Add:
   - Name: `kontra-jenkins-lib`
   - Default version: `main`
   - Retrieval method: Modern SCM → Git → URL to this repo

2. In your Jenkinsfile:

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
  agent any
  stages {
    stage('Version') {
      steps {
        script {
          def v = semver(forceMinor: params.FORCE_MINOR == true)
          echo "version=${v.version}, isRelease=${v.isRelease}"
        }
      }
    }
    // Optional: gate a GitHub Release stage by IS_RELEASE (set when commit contains !release)
    stage('Release') {
      when { expression { env.IS_RELEASE == 'true' } }
      steps {
        script {
          release(version: env.BUILD_VERSION,
                  credentialsId: 'github-token-or-app')
        }
      }
    }
  }
}
```

## semver

Computes a version based on the latest tag and/or `version.txt`, with optional forced bumps:

- Inputs (selected):
  - `forceBump: 'major'|'minor'|'patch'` or `forceMajor/forceMinor/forcePatch: true`
  - `strategy: 'tag'|'file'` (hybrid tag/file baseline by default unless `strictTagBaseline: true`)
  - `tagPattern: 'v[0-9]*'`, `tagMode: 'nearest'|'latest'`
  - `cumulativePatch: true` → adds commits since last tag to patch when patch bumping
  - `writeFile: true` (writes `version.txt`), `stateFile: '.semver-state'`
  - `skipOnSameCommit: true` (prevents re-bump on same commit unless forced)

- Tokens in the last commit message: `!major`, `!minor` (default is patch). `!release` sets `IS_RELEASE` metadata (used to gate a release stage).

- Returns (selected): `version`, `bump`, `baseVersion`, `baselineSource`, `commitsSinceTag`, `skipped`, `isRelease`, `forcedBump`, `branch`.

Example:

```groovy
def v = semver(cumulativePatch: true, tagPattern: 'v[0-9]*')
echo "Next: ${v.version} (from ${v.baselineSource})"
```

## release

Creates an annotated tag and pushes it, and optionally creates/updates a GitHub Release.

Tokens and behavior:

- `!tag` → create and push the tag (subject to branch gates). No GitHub Release.
- `!release` → create/update a GitHub Release. If the tag for the current version doesn’t exist, it will be created and pushed first (subject to branch gates).
- Backward-compatible aliases are supported for releases: `!ghrelease`, `!github-release`.

Tagging gates:

- `alwaysTag: true` → always tag
- `onlyTagOnMain: true` (default) restricts to `mainBranch: 'main'`
- Force: `forceRelease: true` will tag even without `!tag`.

Git push auth:

- Provide `credentialsId` (GitHub App or PAT) for HTTPS push; else it tries a plain `git push origin` using agent auth.

GitHub Release trigger:

- Commit contains `!release` (or aliases) OR `forceGithubRelease: true`.
- `createGithubRelease` is capability-only; it doesn’t auto-trigger a release.
- If the tag doesn’t exist yet and `!release` is present, the step creates and pushes the tag first.
- Release payload options: `releaseDraft`, `prerelease`, `generateReleaseNotes` or `attachCommitNotes` (simple commit list, default on).

Example:

```groovy
release(
  version: env.BUILD_VERSION,
  credentialsId: 'github-token-or-app',
  alwaysTag: false,
  onlyTagOnMain: true,
  generateReleaseNotes: true // or attachCommitNotes: true (default)
)
```

Notes:

- GitHub App must have Repository permissions → Contents: Read and write; installation must include the repo.
- Protected tag rules may block creation; grant bypass or use an actor with bypass.
- If App tokens don’t push, use a PAT from a machine user with write access.

## generateChangelog

Builds a small markdown section from recent commits.

- Inputs:
  - `outputFile: 'CHANGELOG.md'`, `copyTo` (optional second path), `title`, `version`
  - Range selection: `since` (tag or ref); else uses Jenkins previous commit env; else nearest tag; else last 50 commits
  - `tagPattern: 'v[0-9]*'`, `maxCommits: 0 (no limit)`
  - `resetToken: '!resetLog'` to re-init file

Example:

```groovy
generateChangelog(version: env.BUILD_VERSION, outputFile: 'CHANGELOG.md', maxCommits: 50)
```

## writeEnvFile

Writes a POSIX-friendly `.env` file with quoting/escaping.

- Inputs:
  - `path` (required), `data` (Map), `keys` (List or string), `sortKeys: true`
  - `mode: '600'`, `owner`, `group`, `useSudo: true`, `dryRun: false`

Example:

```groovy
writeEnvFile path: '/opt/app/.env', data: [DB_URL: 'jdbc:...', FEATURE_X: 'true'], mode: '640', owner: 'app', group: 'app'
```

## deploySystemd

Deploy any service as a systemd unit. Three ways to define how the service starts:

1) Provide `execStart` directly (used as-is)
2) Provide `startCommand` → written to `<workingDir>/launch.sh` and used
3) Include a repo `launch.sh` (configurable via `repoLaunchScript`, default `'launch.sh'`); it’s installed to `<workingDir>/launch.sh` and used

- Common inputs:
  - `service` (required), `description`, `workingDir` (default `/opt/<service>`)
  - `envFile`, `installUnit: true`, `overwriteUnit: true`, `enable: true`
  - `useUserUnit: false` (systemd --user), `useSudo: true`
  - Artifacts (optional): `artifactGlob` (copy newest), or `jarPath`/`targetDir`/`targetName`
  - Java defaults (only if you don’t use launch.sh/startCommand): `javaBin: '/usr/bin/java'`, `javaOpts`, `appArgs`

- Returns: includes `unitContent` (dry run or after write), `execStart`, optional `deployedJar`, `launchScript` and `launchScriptContent` when applicable.

Examples:

```groovy
// Use repo launch.sh
deploySystemd service: 'api', workingDir: '/opt/api', installUnit: true, useSudo: true

// Provide a simple start command (non-Java project)
deploySystemd service: 'worker', workingDir: '/opt/worker', startCommand: 'node server.js'

// Traditional Java jar auto-synthesis
deploySystemd service: 'java-svc', artifactGlob: 'build/libs/*.jar', user: 'app', group: 'app'
```

## End-to-end example

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
  agent any
  options { timestamps() }
  stages {
    stage('Version') {
      steps { script { semver(cumulativePatch: true) } }
    }
    stage('Env') {
      steps { writeEnvFile path: 'app.env', data: [PORT: '8080'], dryRun: true }
    }
    stage('Changelog') {
      steps { generateChangelog(version: env.BUILD_VERSION, outputFile: 'CHANGELOG.md', maxCommits: 50) }
    }
    // Tagging on !tag
    stage('Tag') {
      steps { script { release(version: env.BUILD_VERSION, credentialsId: 'github-token') } }
    }
    // GitHub Release on !release (also ensures tag exists)
    stage('Release') {
      when { expression { env.IS_RELEASE == 'true' } }
      steps { script { release(version: env.BUILD_VERSION, credentialsId: 'github-token', generateReleaseNotes: true) } }
    }
    stage('Deploy (dry run)') {
      steps {
        deploySystemd service: 'api', workingDir: '/opt/api', repoLaunchScript: 'launch.sh', installUnit: true, dryRun: true
      }
    }
  }
}
```

## Testing Locally

Project uses JUnit + Jenkins Pipeline Unit.

```bash
gradle test
```

Reports: `build/reports/tests/test/index.html`

## Conventions

- Library steps live under `vars/` (one file per step; entrypoint is `call`).
- Tests live in `test/groovy/*Test.groovy`.
- Prefer adding a `dryRun` flag for steps that perform remote/system changes.

## Troubleshooting

- GitHub push 403 when tagging: ensure your credential (App or PAT) has write access to the repo, and tag rules don’t block creation.
- Groovy CPS parsing: avoid complex quoting in Jenkinsfile params; this library avoids regex-heavy constructs in steps.

## License

Internal use. Add a license if publishing externally.
