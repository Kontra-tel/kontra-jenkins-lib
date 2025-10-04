# Kontra Jenkins Shared Library

Reusable Jenkins Pipeline steps for versioning, ## release

Creates an annotated tag and pushes it, and optionally creates/updates a GitHub Release.

**Tokens**

- **`!tag`** → Create and push the git t- Protected tag rules may block creation; grant bypass or use an actor with bypass.
- If App tokens don't push, use a PAT from a machine user with write access.

## shouldBuild

Determines if a build should proceed based on commit message tokens. Useful for token-driven workflows where builds should only run when specific tokens are present, avoiding unnecessary builds on every commit.

**Parameters:**

- `requiredTokens: ['!tag', '!release']` - List of tokens to check for (default: `['!tag', '!release']`)
- `anyToken: false` - If `true`, match ANY token; if `false` (default), match ALL tokens
- `force: false` - Force build regardless of tokens (can also use `env.FORCE_BUILD`)
- `verbose: true` - Enable verbose logging (default: `true`)

**Returns:** `true` if build should proceed, `false` otherwise

**Usage (skip build if no tokens):**

```groovy
stage('Check') {
  steps {
    script {
      if (!shouldBuild(requiredTokens: ['!tag', '!release'], anyToken: true)) {
        currentBuild.result = 'NOT_BUILT'
        error('No required tokens found. Build skipped.')
      }
    }
  }
}
```

**Usage (with parameters):**

```groovy
pipeline {
  agent any
  
  parameters {
    booleanParam(name: 'FORCE_BUILD', defaultValue: false, description: 'Force build regardless of tokens')
  }
  
  stages {
    stage('Check') {
      steps {
        script {
          // Build only if commit has !release OR !tag
          if (!shouldBuild(requiredTokens: ['!release', '!tag'], anyToken: true)) {
            currentBuild.result = 'NOT_BUILT'
            error('Build skipped - no release tokens in commit message')
          }
        }
      }
    }
    
    stage('Build') {
      steps {
        echo "Building..."
      }
    }
  }
}
```

**Example commit messages:**

```bash
# Will NOT build (no tokens)
git commit -m "Fix typo in README"

# WILL build (!release token present)
git commit -m "Add new feature !minor !release"

# WILL build (!tag token present)
git commit -m "Bugfix !patch !tag"

# Force build from Jenkins UI (set FORCE_BUILD=true parameter)
```

## generateChangelog

Builds a small markdown section from recent commits.

- Inputs:
  - `outputFile: 'CHANGELOG.md'`, `copyTo` (optional second path), `title`, `version`
  - Range selection: `since` (tag or ref); else uses Jenkins previous commit env; else nearest tag; else last 50 commitst to branch gates)
- **`!release`** → Create GitHub Release (requires tag to exist on remote; will auto-tag if needed)
- **`!no-ghrelease`** → Suppress GitHub Release creation even if configured
- Aliases for `!release`: `!ghrelease`, `!github-release`

**Tagging behavior**

- `alwaysTag: true` → tag every build (ignores tokens)
- `alwaysTag: false` → tag only when:
  - Commit contains `!tag` OR
  - `forceRelease: true` parameter is set
- `onlyTagOnMain: true` (default) → restrict tagging to `mainBranch` (default: `'main'`)
- `onlyTagOnMain: false` → allow tagging on any branch

**GitHub Release behavior**

- Created when ALL conditions are met:
  - `credentialsId` is provided (GitHub App or PAT)
  - NOT suppressed by `!no-ghrelease` token
  - AND one of:
    - `createGithubRelease: true` parameter
    - Commit contains `!release` (or aliases)
    - `forceGithubRelease: true` parameter

**Git push auth**nment files, changelog generation, and systemd deployment.

## Provided Steps

| Step | Purpose |
|------|---------|
| `semver` | Compute the next semantic version (with forced bumps, hybrid tag/file baseline, skip-on-same-commit). Exports `env.BUILD_VERSION`. |
| `release` | Create/push a git tag and optionally create/update a GitHub Release. |
| `shouldBuild` | Determine if build should proceed based on commit message tokens (for selective triggering). |
| `generateChangelog` | Generate or append a changelog section from recent commits. |
| `writeEnvFile` | Write a sanitized .env-style file from key/value pairs (supports dryRun). |
| `deploySystemd` | Deploy a service unit (generic; supports repo `launch.sh` or custom command; supports dryRun). |

## Install as a Global Library

1. In Jenkins, Manage Jenkins → Global Pipeline Libraries → Add:
   - Name: `kontra-jenkins-lib`
   - Default version: `main`
   - Retrieval method: Modern SCM → Git → URL to this repo

## Workflow Patterns

### Token-Driven Releases (for non-CI/CD apps)

For apps like posting services where you don't need continuous integration on every commit, use a **token-driven** workflow with the `shouldBuild` step:

**Setup:**

1. **Add `shouldBuild` check** as first stage to skip builds without tokens
2. **Set `alwaysTag: false`** to require explicit `!tag` token
3. **Set `defaultBump: 'none'`** in semver to require version bump tokens

**Workflow:**

```bash
# Development: Make changes without triggering Jenkins
git commit -m "Fix bug in posting logic"
git push
# → Jenkins runs but shouldBuild skips the build (NOT_BUILT)

# Ready to release: Use tokens in commit message
git commit -m "Add new feature !minor !tag !release"
git push
# → Jenkins runs and proceeds with full build + release
# 1. Detect !minor → bump to 1.2.0
# 2. Detect !tag → create and push git tag
# 3. Detect !release → create GitHub Release
```

**Pipeline configuration:**

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
  agent any
  
  parameters {
    booleanParam(name: 'FORCE_BUILD', defaultValue: false, description: 'Force build regardless of tokens')
    booleanParam(name: 'ALWAYS_TAG', defaultValue: false, ...)
  }
  
  stages {
    stage('Check') {
      steps {
        script {
          // Skip build if no release tokens present
          if (!shouldBuild(requiredTokens: ['!tag', '!release'], anyToken: true)) {
            currentBuild.result = 'NOT_BUILT'
            error('Build skipped - no release tokens in commit message')
          }
        }
      }
    }
    
    stage('Version') {
      steps {
        script {
          semver(defaultBump: 'none')  // Requires !major, !minor, or !patch token
        }
      }
    }
    
    stage('Build') {
      steps {
        echo "Building ${env.BUILD_VERSION}..."
      }
    }
    
    stage('Release') {
      steps {
        script {
          release(
            version: env.BUILD_VERSION,
            alwaysTag: params.ALWAYS_TAG,     // false by default
            credentialsId: 'github-creds'
          )
        }
      }
    }
  }
}
```

### CI/CD Pattern (build on every commit)

For continuous integration, enable automatic builds and tagging:

**Setup:**

1. **Enable SCM polling** or webhooks in `triggers` block
2. **Set `alwaysTag: true`** to tag every build
3. **Set `defaultBump: 'patch'`** for automatic patch bumps

**Pipeline configuration:**

```groovy
triggers {
  pollSCM('H/5 * * * *')  // Poll every 5 minutes
}

parameters {
  booleanParam(name: 'ALWAYS_TAG', defaultValue: true, ...)
}

// semver with defaultBump: 'patch' (default)
// release with alwaysTag: true
```

## Usage Examples

1. In your Jenkinsfile:

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

Computes a version based on the latest tag and/or `version.txt`, with optional forced bumps.

- Inputs (selected):
  - `forceBump: 'major'|'minor'|'patch'` or `forceMajor/forceMinor/forcePatch: true`
  - `strategy: 'tag'|'file'` (hybrid tag/file baseline by default unless `strictTagBaseline: true`)
  - `tagPattern: 'v[0-9]*'`, `tagMode: 'nearest'|'latest'`
  - `cumulativePatch: true` → adds commits since last tag to patch when patch bumping
  - `writeFile: true` (writes `version.txt`), `stateFile: '.semver-state'`
  - `skipOnSameCommit: true` (prevents re-bump on same commit unless forced)
  - `defaultBump: 'patch'|'none'` (set to `'none'` to require tokens)
  - `patchToken: '!patch'` (explicit patch bump token)

- Tokens in the last commit message: `!major`, `!minor`, optional `!patch`.
  - If `defaultBump: 'patch'` (default), patch bumps still occur when no token is present.
  - If `defaultBump: 'none'`, no bump unless a token is present or `force*` is set.

- Returns (selected): `version`, `bump`, `baseVersion`, `baselineSource`, `commitsSinceTag`, `skipped`, `isRelease`, `forcedBump`, `branch`.

Example (token-required bumping):

```groovy
def v = semver(defaultBump: 'none')
```

Example (explicit patch token):

```groovy
def v = semver(defaultBump: 'none', patchToken: '!patch')
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
