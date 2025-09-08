# Kontra Jenkins Shared Library

Reusable Jenkins Pipeline steps for internal projects.

## Provided Steps

| Step | Purpose |
|------|---------|
| `deploySystemd` | Deploy a (Java) application as a systemd service (supports dryRun). |
| `writeEnvFile`  | Generate a sanitized `.env` style file from key/value data (supports dryRun). |
| `generateChangelog` | Append a changelog section from current build change sets. |
| `semver` | (If present) Utility functions for semantic version handling. |

## Quick Usage

1. In your Jenkins controller configure a Global Pipeline Library pointing to this repo (default branch `main`).
2. In a Jenkinsfile of another project:

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
	agent any
	stages {
		stage('Env File') {
			steps {
				writeEnvFile path: 'app.env', data: [FOO: 'bar', PORT: '8080'], dryRun: true
			}
		}
		stage('Deploy') {
			steps {
				deploySystemd service: 'myapp', artifactGlob: 'build/libs/*.jar', installUnit: true, dryRun: true
			}
		}
	}
}
```

Remove `dryRun: true` to perform real actions on the agent.

## `deploySystemd` Highlights

Minimal example (auto-synthesizes `ExecStart`):

```groovy
deploySystemd service: 'api', artifactGlob: 'build/libs/*.jar', user: 'api', group: 'api'
```

Custom script:

```groovy
deploySystemd service: 'api', execStart: "/usr/bin/env bash -lc 'cd /opt/api && ./start.sh'"
```

## `writeEnvFile` Highlights

```groovy
writeEnvFile path: '/opt/app/.env', data: [DB_URL: 'jdbc:...', FEATURE_X: 'true'], mode: '640', owner: 'app', group: 'app'
```
Dry run returns the composed content without installing.

## Testing Locally

Project uses JUnit + Jenkins Pipeline Unit.
```bash
gradle test
```
Reports: `build/reports/tests/test/index.html`

## Gradle Wrapper (Optional)
Generate wrapper if you want reproducible builds:
```bash
gradle wrapper --gradle-version 8.13
```
Commit the generated `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.

## Conventions
* Library steps live under `vars/` (one file per step; the function entrypoint is `call`).
* Tests live in `test/groovy/*Test.groovy`.
* Prefer adding a `dryRun` flag for steps that perform remote/system changes.

## License
Internal use. Add a license section if publishing externally.
