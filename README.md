# Kontra Jenkins Shared Library

Reusable Jenkins Pipeline steps for versioning, releases, deployments, and automation.

## Quick Start

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Version') {
            steps {
                semver(cumulativePatch: true)
            }
        }
        stage('Build') {
            when {
                expression { shouldBuild(requiredTokens: ['!release']) }
            }
            steps {
                sh './gradlew build'
            }
        }
        stage('Deploy') {
            steps {
                deploySystemd(
                    service: 'my-app',
                    artifactGlob: 'build/libs/*.jar',
                    workingDir: '/opt/my-app'
                )
            }
        }
        stage('Release') {
            steps {
                release(releaseDraft: false)
            }
        }
    }
}
```

## Available Steps

| Step | Description | Documentation |
|------|-------------|---------------|
| `deploySystemd` | Deploy services as systemd user units | [Docs](docs/deploySystemd.md) |
| `restartSystemd` | Manage existing systemd services | [Docs](docs/restartSystemd.md) |
| `writeEnvFile` | Create environment files with proper permissions | [Docs](docs/writeEnvFile.md) |
| `semver` | Semantic versioning with git tags | [Docs](docs/semver.md) |
| `release` | Create GitHub releases with assets | [Docs](docs/release.md) |
| `shouldBuild` | Token-driven build filtering | [Docs](docs/shouldBuild.md) |
| `generateChangelog` | Generate changelogs from commits | [Docs](docs/generateChangelog.md) |
| `uploadReleaseAssets` | Upload files to GitHub releases | [Docs](docs/uploadReleaseAssets.md) |

## Installation

### In Jenkins

1. Go to **Manage Jenkins** → **Configure System**
2. Scroll to **Global Pipeline Libraries**
3. Add library with:
   - Name: `kontra-jenkins-lib`
   - Default version: `main`
   - Retrieval method: **Modern SCM** → Git
   - Project Repository: `https://github.com/your-org/your-repo-name.git`
   - Load implicitly: ☐ (recommended to use `@Library` annotation)

### In Jenkinsfile

```groovy
@Library('kontra-jenkins-lib@main') _
// or
@Library('kontra-jenkins-lib@v1.2.3') _  // Specific version
```

## Common Patterns

### Token-Driven Releases

Use commit message tokens to control builds and releases:

```groovy
stage('Check') {
    when {
        expression { shouldBuild(requiredTokens: ['!release']) }
    }
    steps {
        echo "Building because !release token found"
    }
}
```

**Tokens:**
- `!tag` - Create git tag
- `!release` - Create GitHub release
- `!deploy` - Trigger deployment
- Custom tokens supported

See [shouldBuild docs](docs/shouldBuild.md) for details.

### Service Deployment

Deploy applications as systemd user services:

```groovy
// Simple deployment
deploySystemd(
    service: 'my-api',
    artifactGlob: 'build/libs/*.jar',
    workingDir: '/opt/my-api'
)

// With environment configuration
writeEnvFile(
    path: '/opt/my-api/.env',
    data: [PORT: '8080', DB_URL: 'jdbc:...']
)
deploySystemd(
    service: 'my-api',
    artifactGlob: 'build/libs/*.jar',
    envFile: '/opt/my-api/.env'
)

// Central service user
deploySystemd(
    service: 'my-api',
    runAsUser: 'kontra-service',
    workingDir: '/home/kontra-service/apps/my-api'
)
```

See [deploySystemd docs](docs/deploySystemd.md) for all options.

### Semantic Versioning

Automatic version management with git tags:

```groovy
stage('Version') {
    steps {
        script {
            def ver = semver(cumulativePatch: true)
            echo "Building version ${ver.version}"
        }
    }
}
```

See [semver docs](docs/semver.md) for details.

### GitHub Releases

Create releases with automatic changelog and asset uploads:

```groovy
stage('Release') {
    steps {
        script {
            release(
                tagName: "v${env.VERSION}",
                releaseName: "Release v${env.VERSION}",
                releaseBody: "See CHANGELOG.md for details",
                releaseDraft: false,
                prerelease: false,
                generateReleaseNotes: true,
                assets: ['build/libs/*.jar', 'README.md']
            )
        }
    }
}
```

See [release docs](docs/release.md) for all options.

## Configuration

### Environment Variables

The library respects these environment variables:

- `GITHUB_TOKEN` / `GH_TOKEN` - GitHub authentication (required for releases)
- `FORCE_BUILD` - Force builds regardless of tokens
- `COMMIT_MESSAGE` - Override commit message detection
- `JAVA_OPTS` - Default JVM options for Java deployments

### Systemd Setup

For systemd user services to run 24/7:

```bash
sudo loginctl enable-linger <username>
```

For central service user deployments, see [CENTRAL_SERVICE_USER.md](docs/CENTRAL_SERVICE_USER.md).

## Documentation

### Step Documentation
- [deploySystemd](docs/deploySystemd.md) - Service deployment
- [restartSystemd](docs/restartSystemd.md) - Service management
- [writeEnvFile](docs/writeEnvFile.md) - Environment files
- [semver](docs/semver.md) - Semantic versioning
- [release](docs/release.md) - GitHub releases
- [shouldBuild](docs/shouldBuild.md) - Build filtering
- [generateChangelog](docs/generateChangelog.md) - Changelog generation
- [uploadReleaseAssets](docs/uploadReleaseAssets.md) - Release assets

### Setup Guides
- [CENTRAL_SERVICE_USER.md](docs/CENTRAL_SERVICE_USER.md) - Central service user setup
- [SYSTEMD_PERMISSIONS.md](docs/SYSTEMD_PERMISSIONS.md) - Permission configurations

## Development

### Running Tests

```bash
gradle test
```

### Adding New Steps

1. Create step in `vars/<stepName>.groovy`
2. Add tests in `test/groovy/<StepName>Test.groovy`
3. Create documentation in `docs/<stepName>.md`
4. Update this README with link

## License

MIT

## Contributing

Pull requests welcome! Please ensure:
1. Tests pass (`gradle test`)
2. Documentation included in `docs/`
3. Examples provided
4. Follows existing patterns

## Support

- Issues: https://github.com/Kontra-tel/kontra-jenkins-lib/issues
- Discussions: https://github.com/Kontra-tel/kontra-jenkins-lib/discussions
