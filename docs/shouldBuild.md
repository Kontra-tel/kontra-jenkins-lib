# shouldBuild

Token-based build filtering to control when pipelines should execute.

## Overview

`shouldBuild` determines if a build should proceed based on commit message tokens. This is useful for token-driven workflows where builds should only run when specific markers are present in commit messages.

**Key Features:**
- Token-based build gating
- Flexible matching (ANY or ALL tokens)
- Force build override
- Automatic commit message fetching
- Verbose logging for debugging

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Check Build') {
            steps {
                script {
                    if (!shouldBuild(requiredTokens: ['!tag', '!release'])) {
                        currentBuild.result = 'NOT_BUILT'
                        error('No required tokens found. Build skipped.')
                    }
                }
            }
        }
        
        stage('Build') {
            steps {
                sh './gradlew build'
            }
        }
    }
}
```

## Parameters

### Token Configuration
- `requiredTokens` (List<String>) - List of tokens to check for (default: `['!tag', '!release']`)
- `anyToken` (Boolean) - Match ANY token instead of ALL (default: `false`)

### Override Options
- `force` (Boolean) - Force build regardless of tokens (default: `false`)
  - Also enabled by `env.FORCE_BUILD == 'true'`

### Logging
- `verbose` (Boolean) - Enable detailed logging (default: `true`)

## Return Value

Returns `true` if build should proceed, `false` if it should be skipped.

## Examples

### Basic Token Check (Default Behavior)

```groovy
stage('Check') {
    steps {
        script {
            // Build only if commit has BOTH !tag AND !release
            if (!shouldBuild()) {
                currentBuild.result = 'NOT_BUILT'
                error('Build skipped - no release tokens')
            }
        }
    }
}
```

**Commit examples:**
- `"feat: new feature !tag !release"` → ✓ Builds (has both)
- `"feat: new feature !tag"` → ✗ Skipped (missing !release)
- `"feat: new feature !release"` → ✗ Skipped (missing !tag)
- `"feat: new feature"` → ✗ Skipped (missing both)

### Match ANY Token

```groovy
stage('Check') {
    steps {
        script {
            // Build if commit has !tag OR !release OR !deploy
            if (!shouldBuild(
                requiredTokens: ['!tag', '!release', '!deploy'],
                anyToken: true
            )) {
                currentBuild.result = 'NOT_BUILT'
                error('Build skipped')
            }
        }
    }
}
```

**Commit examples:**
- `"feat: new feature !tag"` → ✓ Builds (has one)
- `"feat: new feature !release"` → ✓ Builds (has one)
- `"feat: new feature !deploy"` → ✓ Builds (has one)
- `"feat: new feature"` → ✗ Skipped (has none)

### Custom Tokens

```groovy
stage('Check') {
    steps {
        script {
            if (!shouldBuild(
                requiredTokens: ['!deploy-prod', '!approved'],
                anyToken: false  // Require ALL tokens
            )) {
                currentBuild.result = 'NOT_BUILT'
                error('Production deployment requires !deploy-prod and !approved')
            }
        }
    }
}
```

### Force Build

```groovy
stage('Check') {
    steps {
        script {
            // Always build, ignore tokens
            if (!shouldBuild(force: true)) {
                // This will never be reached
                error('Unreachable')
            }
        }
    }
}
```

Or via environment variable:

```groovy
pipeline {
    parameters {
        booleanParam(name: 'FORCE_BUILD', defaultValue: false, description: 'Force build')
    }
    
    stages {
        stage('Check') {
            steps {
                script {
                    // Build if tokens present OR FORCE_BUILD parameter is true
                    if (!shouldBuild()) {
                        currentBuild.result = 'NOT_BUILT'
                        error('Build skipped')
                    }
                }
            }
        }
    }
}
```

### Disable Verbose Logging

```groovy
stage('Check') {
    steps {
        script {
            if (!shouldBuild(
                requiredTokens: ['!release'],
                verbose: false  // Quiet mode
            )) {
                currentBuild.result = 'NOT_BUILT'
                error('Build skipped')
            }
        }
    }
}
```

### Integration with Release Workflow

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Check Release') {
            steps {
                script {
                    // Only release builds when explicitly tagged
                    if (!shouldBuild(
                        requiredTokens: ['!tag', '!release'],
                        anyToken: false  // Require both tokens
                    )) {
                        echo "Not a release build, running tests only"
                        env.IS_RELEASE = 'false'
                    } else {
                        env.IS_RELEASE = 'true'
                    }
                }
            }
        }
        
        stage('Test') {
            steps {
                sh './gradlew test'
            }
        }
        
        stage('Build') {
            steps {
                sh './gradlew build'
            }
        }
        
        stage('Release') {
            when {
                expression { env.IS_RELEASE == 'true' }
            }
            steps {
                script {
                    def version = semver(createTag: true)
                    release(
                        version: version.version,
                        credentialsId: 'github-token'
                    )
                }
            }
        }
    }
}
```

### Early Exit Pattern

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Gate') {
            steps {
                script {
                    if (!shouldBuild(requiredTokens: ['!release'])) {
                        currentBuild.result = 'NOT_BUILT'
                        currentBuild.description = 'Skipped - no release token'
                        error('Build skipped')  // This stops the pipeline
                    }
                }
            }
        }
        
        // These stages only run if Gate passed
        stage('Build') { /* ... */ }
        stage('Test') { /* ... */ }
        stage('Deploy') { /* ... */ }
    }
}
```

### Multiple Token Sets

```groovy
stage('Check') {
    steps {
        script {
            boolean isRelease = shouldBuild(
                requiredTokens: ['!release', '!tag'],
                anyToken: false,
                verbose: false
            )
            
            boolean isDeploy = shouldBuild(
                requiredTokens: ['!deploy'],
                anyToken: true,
                verbose: false
            )
            
            env.IS_RELEASE = isRelease.toString()
            env.IS_DEPLOY = isDeploy.toString()
            
            if (!isRelease && !isDeploy) {
                currentBuild.result = 'NOT_BUILT'
                error('Neither release nor deploy - skipping build')
            }
        }
    }
}
```

## Environment Variables

### Used by shouldBuild
- `COMMIT_MESSAGE` - Commit message (cached for reuse)
- `FORCE_BUILD` - If `'true'`, forces build regardless of tokens

### Set by shouldBuild
- `COMMIT_MESSAGE` - Sets this if not already set (for later steps to reuse)

## Token Conventions

Common tokens used across the library:

- `!tag` - Create git tag
- `!release` - Create GitHub release
- `!deploy` - Deploy to environment
- `!major` - Major version bump
- `!minor` - Minor version bump
- `!patch` - Patch version bump
- `!nobuild` - Skip build

## Match Modes

### ALL Mode (anyToken: false)

**Default behavior**: ALL tokens must be present

```groovy
shouldBuild(requiredTokens: ['!tag', '!release'])
```

| Commit Message | Result |
|---------------|--------|
| `"fix: bug !tag !release"` | ✓ Pass |
| `"fix: bug !tag"` | ✗ Fail |
| `"fix: bug !release"` | ✗ Fail |
| `"fix: bug"` | ✗ Fail |

### ANY Mode (anyToken: true)

**Optional behavior**: At least ONE token must be present

```groovy
shouldBuild(requiredTokens: ['!tag', '!release'], anyToken: true)
```

| Commit Message | Result |
|---------------|--------|
| `"fix: bug !tag !release"` | ✓ Pass |
| `"fix: bug !tag"` | ✓ Pass |
| `"fix: bug !release"` | ✓ Pass |
| `"fix: bug"` | ✗ Fail |

## Best Practices

1. **Use Descriptive Tokens** - Choose tokens that clearly indicate intent
2. **Document Token Requirements** - Make it clear what tokens do in your README
3. **Default to Skipping** - Safer to skip builds by default, require explicit tokens
4. **Combine with Branches** - Use both token checks and branch conditions
5. **Provide Force Override** - Always allow forced builds for emergencies

## Troubleshooting

### Commit Message Not Found

**Problem:** "Failed to fetch commit message"

**Solutions:**
- Ensure running in git repository
- Check git is available in PATH
- Verify workspace has git checkout
- Use `force: true` to bypass check

### Builds Always Skipped

**Problem:** Builds never proceed even with tokens

**Solutions:**
- Check token spelling (case-sensitive: `!tag` not `!TAG`)
- Verify `anyToken` mode matches your needs
- Enable `verbose: true` to see matched tokens
- Check `requiredTokens` list is correct

### Builds Always Proceed

**Problem:** Builds run even without tokens

**Solutions:**
- Check for `force: true` in config
- Verify `env.FORCE_BUILD` is not set to `'true'`
- Confirm you're not in `anyToken` mode with empty token list

## See Also

- [semver](semver.md) - Version bumping with similar token support
- [release](release.md) - Release creation with token-based gating
