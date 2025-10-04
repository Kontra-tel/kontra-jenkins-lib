# release

Create git tags and GitHub Releases with automatic release notes and asset uploads.

## Overview

`release` creates git tags (e.g., vX.Y.Z) and optionally creates GitHub Releases with release notes and asset uploads. It provides flexible control over when to tag and when to create releases via commit tokens or force parameters.

**Key Features:**
- Git tag creation and pushing
- GitHub Release creation with notes
- Enhanced changelog integration
- Asset uploading support
- Branch-based gating
- Token-based control
- GitHub App and PAT support
- Draft and prerelease support

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Release') {
            steps {
                script {
                    def ver = semver(writeFile: true)
                    release(
                        version: ver.version,
                        credentialsId: 'github-token',
                        pushTags: true
                    )
                }
            }
        }
    }
}
```

## Parameters

### Required
- `version` (String) - Release version (e.g., `'1.2.3'` or from `env.BUILD_VERSION`)

### Tagging
- `tagPrefix` (String) - Tag prefix (default: `'v'`) → creates tags like `v1.2.3`
- `pushTags` (Boolean) - Push tags to remote (default: `true`)
- `alwaysTag` (Boolean) - Tag every build (default: `false`)
- `tagOnRelease` (Boolean) - Tag when `!tag` token present (default: `true`)
- `onlyTagOnMain` (Boolean) - Restrict tagging to main branch (default: `true`)
- `releaseBranch` / `mainBranch` (String) - Main branch name (default: `'main'`)

### GitHub Release
- `credentialsId` (String) - GitHub credentials ID (GitHub App or PAT)
- `forceGithubRelease` (Boolean) - Always create GitHub Release (default: `false`)
- `createGithubRelease` (Boolean) - Alias for `forceGithubRelease`
- `releaseDraft` (Boolean) - Create as draft (default: `false`)
- `prerelease` (Boolean) - Mark as prerelease (default: `false`)

### Release Notes
- `generateReleaseNotes` (Boolean) - Use GitHub's auto-generated notes (default: `false`)
- `attachCommitNotes` (Boolean) - Attach simple commit list (default: `true`)
- `useChangelogModule` (Boolean) - Use `generateChangelog` for enhanced notes (default: `false`)
- `releaseNotesHeader` (String) - Header for commit list (default: `'Changes since last release:'`)

### Asset Upload
- `assets` (String or List) - Files to upload (glob pattern or list)
- `assetsRename` (Map) - Rename assets: `[sourcePath: newName]`
- `assetOverwrite` (Boolean) - Overwrite existing assets (default: `false`)
- `assetContentType` (String) - Content type (default: `'application/octet-stream'`)

### Git Configuration
- `gitUserName` (String) - Git user name (default: `'Jenkins CI'`)
- `gitUserEmail` (String) - Git email (default: `'jenkins@local'`)

### Advanced
- `owner` (String) - GitHub owner hint (auto-detected if not provided)
- `githubApi` (String) - API endpoint (default: `'https://api.github.com'`)
- `githubUploads` (String) - Upload endpoint (default: `'https://uploads.github.com'`)
- `debug` (Boolean) - Enable debug logging (default: `false`)

## Tokens

### Tagging Tokens
- `!tag` - Create and push git tag

### GitHub Release Tokens
- `!release` - Create GitHub Release (primary)
- `!ghrelease` - Alternative release token
- `!github-release` - Alternative release token
- `!no-ghrelease` - Suppress GitHub Release creation
- `!no-github-release` - Alternative suppression token

## Return Value

Returns a map with:

```groovy
[
    tag: 'v1.2.3',              // Tag name
    tagged: true,               // Whether tag was created
    pushed: true,               // Whether tag was pushed
    githubReleased: true,       // Whether GitHub Release was created
    isRelease: true,            // Whether this is a release build
    ghReleaseRequested: true,   // Whether GH Release was requested
    branch: 'main'              // Current branch
]
```

## Examples

### Basic GitHub Release

```groovy
stage('Release') {
    steps {
        script {
            def ver = semver(writeFile: true)
            
            release(
                version: ver.version,
                credentialsId: 'github-token',
                pushTags: true
            )
        }
    }
}
```

**Requires:** Commit message with `!tag !release` tokens

### Force Release (No Tokens Required)

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: '1.2.3',
                credentialsId: 'github-token',
                forceGithubRelease: true,  // Ignore tokens
                alwaysTag: true            // Always create tag
            )
        }
    }
}
```

### Draft Release

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: env.BUILD_VERSION,
                credentialsId: 'github-token',
                releaseDraft: true,  // Create as draft
                pushTags: true
            )
        }
    }
}
```

### Prerelease

```groovy
stage('Release') {
    steps {
        script {
            def ver = semver()
            
            release(
                version: "${ver.version}-beta.1",
                credentialsId: 'github-token',
                prerelease: true,  // Mark as prerelease
                pushTags: true
            )
        }
    }
}
```

### Enhanced Release Notes

```groovy
stage('Release') {
    steps {
        script {
            def ver = semver(writeFile: true)
            
            release(
                version: ver.version,
                credentialsId: 'github-token',
                useChangelogModule: true,  // Use generateChangelog
                pushTags: true
            )
        }
    }
}
```

This generates categorized release notes with Features, Bug Fixes, Documentation, etc.

### With Asset Upload

```groovy
stage('Build') {
    steps {
        sh './gradlew build'
    }
}

stage('Release') {
    steps {
        script {
            release(
                version: env.BUILD_VERSION,
                credentialsId: 'github-token',
                pushTags: true,
                assets: 'build/libs/*.jar',  // Upload JARs
                assetOverwrite: true
            )
        }
    }
}
```

### Rename Uploaded Assets

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: '1.2.3',
                credentialsId: 'github-token',
                assets: ['build/libs/app-1.2.3.jar', 'docs/manual.pdf'],
                assetsRename: [
                    'build/libs/app-1.2.3.jar': 'myapp.jar',
                    'docs/manual.pdf': 'user-manual.pdf'
                ]
            )
        }
    }
}
```

### GitHub Auto-Generated Notes

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: env.BUILD_VERSION,
                credentialsId: 'github-token',
                generateReleaseNotes: true,  // Use GitHub's auto-generation
                attachCommitNotes: false     // Don't add simple commit list
            )
        }
    }
}
```

### Tag Only (No GitHub Release)

```groovy
stage('Tag') {
    steps {
        script {
            release(
                version: '1.2.3',
                alwaysTag: true,
                pushTags: true
                // No credentialsId = no GitHub Release
            )
        }
    }
}
```

### Branch-Specific Releases

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: env.BUILD_VERSION,
                credentialsId: 'github-token',
                onlyTagOnMain: true,      // Only tag on main
                releaseBranch: 'main',
                pushTags: true
            )
        }
    }
}
```

### Token-Based Workflow

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Version') {
            steps {
                script {
                    env.SEMVER_RESULT = semver(defaultBump: 'patch')
                }
            }
        }
        
        stage('Build') {
            steps {
                sh './gradlew build -Pversion=${env.BUILD_VERSION}'
            }
        }
        
        stage('Release') {
            steps {
                script {
                    // Only creates release if commit has !tag and !release
                    release(
                        version: env.BUILD_VERSION,
                        credentialsId: 'github-token',
                        pushTags: true
                    )
                }
            }
        }
    }
}
```

**Usage:**
- Regular commit: `"fix: bug"` → Builds, no release
- Release commit: `"fix: critical !tag !release"` → Builds + creates release

## GitHub Credentials

### GitHub App (Recommended)

```groovy
release(
    version: '1.2.3',
    credentialsId: 'github-app-kontra',  // GitHub App credential
    pushTags: true
)
```

**Setup:**
1. Create GitHub App with Releases permission
2. Install app on repository
3. Add app credentials to Jenkins
4. Use credential ID

### Personal Access Token (PAT)

```groovy
release(
    version: '1.2.3',
    credentialsId: 'github-pat',  // String credential with PAT
    pushTags: true
)
```

**Setup:**
1. Generate PAT with `repo` scope
2. Add as Jenkins String credential
3. Use credential ID

## Release Notes Types

### Simple Commit List (Default)

```groovy
release(
    version: '1.2.3',
    credentialsId: 'github-token',
    attachCommitNotes: true  // Default
)
```

**Output:**
```
Changes since last release:
- fix: critical bug (abc1234)
- feat: new feature (def5678)
- docs: update readme (ghi9012)
```

### Enhanced Changelog

```groovy
release(
    version: '1.2.3',
    credentialsId: 'github-token',
    useChangelogModule: true
)
```

**Output:**
```
### Features
- feat: new feature (def5678)

### Bug Fixes
- fix: critical bug (abc1234)

### Documentation
- docs: update readme (ghi9012)
```

### GitHub Auto-Generated

```groovy
release(
    version: '1.2.3',
    credentialsId: 'github-token',
    generateReleaseNotes: true
)
```

Uses GitHub's built-in release notes generation.

## Integration Patterns

### Full Release Workflow

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    parameters {
        booleanParam(name: 'FORCE_RELEASE', defaultValue: false, description: 'Force release')
    }
    
    stages {
        stage('Version') {
            steps {
                script {
                    def ver = semver(
                        writeFile: true,
                        forceRelease: params.FORCE_RELEASE
                    )
                    env.BUILD_VERSION = ver.version
                    env.IS_RELEASE = ver.isRelease.toString()
                }
            }
        }
        
        stage('Build') {
            steps {
                sh "./gradlew build -Pversion=${env.BUILD_VERSION}"
            }
        }
        
        stage('Test') {
            steps {
                sh './gradlew test'
            }
        }
        
        stage('Release') {
            when {
                expression { env.IS_RELEASE == 'true' }
            }
            steps {
                script {
                    release(
                        version: env.BUILD_VERSION,
                        credentialsId: 'github-token',
                        useChangelogModule: true,
                        assets: 'build/libs/*.jar',
                        pushTags: true
                    )
                }
            }
        }
    }
}
```

## Environment Variables

### Used by release
- `BUILD_VERSION` - Default version if not provided
- `BRANCH_NAME` - Current branch (Jenkins)
- `GIT_BRANCH` - Current branch (alternative)
- `FORCE_GH_RELEASE` - Force GitHub Release if `'true'`

## Best Practices

1. **Use Semver** - Integrate with `semver()` for version management
2. **Token-Based** - Use tokens for controlled releases
3. **Test Before Release** - Run tests before release stage
4. **Enhanced Notes** - Enable `useChangelogModule` for better release notes
5. **Branch Protection** - Use `onlyTagOnMain` to protect release process
6. **Asset Management** - Upload build artifacts for distribution

## Troubleshooting

### Tag Already Exists

**Problem:** "tag v1.2.3 already exists locally"

**Solutions:**
- Tag is reused if it exists
- Delete tag locally: `git tag -d v1.2.3`
- Check version is incrementing correctly

### GitHub Release Not Created

**Problem:** Tag created but no GitHub Release

**Solutions:**
- Verify `credentialsId` is provided
- Check commit has `!release` token (or use `forceGithubRelease`)
- Ensure tag was pushed (`pushTags: true`)
- Check credentials have Releases permission

### Asset Upload Failed

**Problem:** Assets not attached to release

**Solutions:**
- Verify asset files exist
- Check glob pattern matches files
- Enable `assetOverwrite` if assets exist
- Verify GitHub credentials have write access

### Wrong Branch Tagged

**Problem:** Tags created on feature branches

**Solutions:**
- Set `onlyTagOnMain: true`
- Verify `releaseBranch` matches your main branch
- Check branch detection logic

## See Also

- [semver](semver.md) - Version management for releases
- [generateChangelog](generateChangelog.md) - Enhanced release notes
- [uploadReleaseAssets](uploadReleaseAssets.md) - Asset upload details
- [ENHANCED_RELEASE_NOTES](ENHANCED_RELEASE_NOTES.md) - Complete guide to enhanced notes
