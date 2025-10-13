# semver

Semantic versioning with automatic version bumping based on commit message tokens.

## Overview

`semver` computes semantic version strings (MAJOR.MINOR.PATCH) and optionally writes version files. It determines version bumps from commit message tokens or force parameters, with support for multiple versioning strategies.

**Key Features:**
- Automatic version bumping from commit messages
- Multiple strategies: tag-based, file-based, or hybrid
- Cumulative patch numbers (commits since last tag)
- State tracking to prevent double-bumps
- Release detection and marking
- Version file management
- No GitHub API dependencies

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Version') {
            steps {
                script {
                    def ver = semver()
                    echo "Version: ${ver.version}"
                    env.BUILD_VERSION = ver.version
                }
            }
        }
    }
}
```

## Parameters

### Version Control
- `versionFile` (String) - Version file path (default: `'version.txt'`)
- `writeFile` (Boolean) - Write version to file (default: `true`)
- `stateFile` (String) - State file for double-bump prevention (default: `'.semver-state'`)

### Tokens
- `majorToken` (String) - Major bump token (default: `'!major'`)
- `minorToken` (String) - Minor bump token (default: `'!minor'`)
- `patchToken` (String) - Patch bump token (default: `'!patch'`)

### Strategy
- `strategy` (String) - Versioning strategy: `'tag'` or `'file'` (default: `'tag'`)
- `strictTagBaseline` (Boolean) - Ignore version.txt for baseline (default: `false`)
- `tagPattern` (String) - Git tag pattern to match (default: `'v[0-9]*'`)
- `tagMode` (String) - Tag selection: `'nearest'` or `'latest'` (default: `'nearest'`)

### Bump Control
- `defaultBump` (String) - Default bump when no token: `'patch'` or `'none'` (default: `'none'`)
- `cumulativePatch` (Boolean) - Add commits since tag to patch number (default: `false`)
- `forceBump` (String) - Force bump type: `'major'`, `'minor'`, or `'patch'` (optional)
- `forceMajor` (Boolean) - Force major bump (default: `false`)
- `forceMinor` (Boolean) - Force minor bump (default: `false`)
- `forcePatch` (Boolean) - Force patch bump (default: `false`)
- `forceRelease` (Boolean) - Mark as release (default: `false`)

### Advanced
- `skipOnSameCommit` (Boolean) - Skip re-bump for same commit (default: `true`)

## Return Value

Returns a map with:

```groovy
[
    baseVersion: '1.2.3',        // Version before bump
    baselineSource: 'tag',       // 'tag' or 'file'
    version: '1.2.4',            // New version
    bump: 'patch',               // 'major', 'minor', 'patch', or 'none'
    commitsSinceTag: 0,          // Number of commits since last tag
    cumulativePatch: false,      // Whether cumulative patch was used
    commitMessage: 'fix: bug',   // Last commit message
    branch: 'main',              // Current branch
    skipped: false,              // Whether bump was skipped
    isRelease: false,            // Whether this is a release
    forcedBump: ''               // Forced bump type if any
]
```

## Version Bump Tokens

Add tokens to commit messages to control version bumps:

- `!major` - Increment major version (1.2.3 → 2.0.0)
- `!minor` - Increment minor version (1.2.3 → 1.3.0)
- `!patch` - Increment patch version (1.2.3 → 1.2.4)
- `!release` - Mark as release build (doesn't bump version)

## Examples

### Basic Version Bumping

```groovy
stage('Version') {
    steps {
        script {
            // Auto-detect version from tags
            def ver = semver()
            echo "New version: ${ver.version}"
            echo "Bump type: ${ver.bump}"
        }
    }
}
```

**Commit examples:**
- `"fix: critical bug !patch"` → 1.2.3 → 1.2.4 (patch bump)
- `"feat: new feature !minor"` → 1.2.3 → 1.3.0 (minor bump)
- `"breaking: API change !major"` → 1.2.3 → 2.0.0 (major bump)
- `"docs: update readme"` → 1.2.3 → 1.2.3 (no bump, defaultBump='none')

### Auto Patch Bumping

```groovy
stage('Version') {
    steps {
        script {
            // Automatically bump patch on every commit
            def ver = semver(defaultBump: 'patch')
            echo "Version: ${ver.version}"
        }
    }
}
```

**Behavior:**
- Commits without tokens → automatic patch bump
- Commits with `!minor` or `!major` → follow token
- Version always increases

### Cumulative Patch

```groovy
stage('Version') {
    steps {
        script {
            // Patch = commits since last tag
            def ver = semver(cumulativePatch: true)
            echo "Version: ${ver.version}"
            echo "Commits since tag: ${ver.commitsSinceTag}"
        }
    }
}
```

**Example:**
- Last tag: `v1.2.0`
- 5 commits since tag
- Result: `v1.2.5` (patch = base patch + 5 commits)

### Force Version Bump

```groovy
stage('Version') {
    steps {
        script {
            // Force minor bump regardless of commit message
            def ver = semver(
                forceMinor: true
            )
            echo "Forced minor version: ${ver.version}"
        }
    }
}
```

Or via pipeline parameter:

```groovy
pipeline {
    parameters {
        choice(name: 'FORCE_BUMP', choices: ['none', 'patch', 'minor', 'major'])
    }
    
    stages {
        stage('Version') {
            steps {
                script {
                    def forceBump = params.FORCE_BUMP != 'none' ? params.FORCE_BUMP : null
                    def ver = semver(forceBump: forceBump)
                    env.BUILD_VERSION = ver.version
                }
            }
        }
    }
}
```

### File-based Strategy

```groovy
stage('Version') {
    steps {
        script {
            // Use version.txt only, ignore git tags
            def ver = semver(
                strategy: 'file',
                versionFile: 'version.txt'
            )
            echo "File-based version: ${ver.version}"
        }
    }
}
```

### Strict Tag Baseline

```groovy
stage('Version') {
    steps {
        script {
            // Use tags only, ignore version.txt
            def ver = semver(
                strategy: 'tag',
                strictTagBaseline: true
            )
            echo "Tag-based version: ${ver.version}"
        }
    }
}
```

### Hybrid Strategy (Default)

```groovy
stage('Version') {
    steps {
        script {
            // Use max(latest_tag, version.txt) as baseline
            def ver = semver(
                strategy: 'tag',
                strictTagBaseline: false  // Default
            )
            echo "Hybrid version: ${ver.version}"
        }
    }
}
```

**Behavior:**
- Compares latest tag vs. version.txt
- Uses whichever is higher
- Keeps bumps "sticky" across builds

### Release Detection

```groovy
stage('Version') {
    steps {
        script {
            def ver = semver()
            
            if (ver.isRelease) {
                echo "This is a RELEASE build"
                env.IS_RELEASE = 'true'
            } else {
                echo "This is a regular build"
                env.IS_RELEASE = 'false'
            }
        }
    }
}
```

**Release triggers:**
- Commit contains `!release`
- `forceRelease: true` parameter
- `env.FORCE_RELEASE == 'true'`

### Integration with Release

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Version') {
            steps {
                script {
                    def ver = semver(
                        writeFile: true,
                        defaultBump: 'patch'
                    )
                    env.BUILD_VERSION = ver.version
                }
            }
        }
        
        stage('Build') {
            steps {
                // Use Groovy interpolation or shell expansion, both shown below:
                // sh "./gradlew build -Pversion=${env.BUILD_VERSION}"
                sh './gradlew build -Pversion=$BUILD_VERSION'
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
                        pushTags: true
                    )
                }
            }
        }
    }
}
```

## Versioning Strategies

### Tag Strategy (Default)

Uses git tags as baseline:

1. Find latest tag matching `tagPattern`
2. If `strictTagBaseline=false`, compare with version.txt
3. Use higher version as baseline
4. Apply bump

**Best for:** Projects using git tags for releases

### File Strategy

Uses version.txt only:

1. Read version from `versionFile`
2. Apply bump
3. Write back to file

**Best for:** Projects without git tags, continuous versioning

## State Tracking

The `.semver-state` file prevents double-bumping:

- Stores last commit SHA that was bumped
- If current commit matches, reuses last version
- Prevents version increment on re-runs
- Can be disabled with `skipOnSameCommit: false`

**Example:**

```text
# Build 1 on commit abc123
semver() → 1.2.4 (bumped from 1.2.3)

# Rebuild same commit abc123
semver() → 1.2.4 (reused, no bump)
```

## Environment Variables

### Set by semver

- `BUILD_VERSION` - Computed version string
- `IS_RELEASE` - `'true'` if release, `'false'` otherwise
- `COMMIT_MESSAGE` - Last commit message (cached)

### Read by semver

- `FORCE_MAJOR` - Force major bump if `'true'`
- `FORCE_MINOR` - Force minor bump if `'true'`
- `FORCE_PATCH` - Force patch bump if `'true'`
- `FORCE_RELEASE` - Mark as release if `'true'`

## Tag Modes

### Nearest (Default)

Uses `git describe` to find nearest reachable tag:

```groovy
semver(tagMode: 'nearest')
```

**Behavior:** Finds closest ancestor tag in commit history

### Latest

Uses version sort to find latest tag by version number:

```groovy
semver(tagMode: 'latest')
```

**Behavior:** Finds highest version number, regardless of commit history

## Best Practices

1. **Use Tags** - Tag releases for proper version tracking
2. **Consistent Tokens** - Document token usage in team guidelines
3. **Write Version File** - Keep version.txt for reproducibility
4. **Enable State** - Use state file to prevent double-bumps
5. **Default to None** - Set `defaultBump: 'none'` to require explicit tokens
6. **Cumulative for Snapshots** - Use `cumulativePatch` for snapshot builds

## Common Patterns

### Semantic Release Pattern

```groovy
stage('Version') {
    steps {
        script {
            def ver = semver(
                defaultBump: 'none',  // Require explicit tokens
                writeFile: true,
                cumulativePatch: false
            )
            
            if (ver.bump == 'none') {
                currentBuild.result = 'NOT_BUILT'
                error('No version bump token found')
            }
        }
    }
}
```

### Snapshot Builds

```groovy
stage('Version') {
    steps {
        script {
            def ver = semver(
                defaultBump: 'patch',
                cumulativePatch: true
            )
            
            env.BUILD_VERSION = ver.isRelease ? ver.version : "${ver.version}-SNAPSHOT"
        }
    }
}
```

### Manual Version Control

```groovy
pipeline {
    parameters {
        choice(name: 'BUMP_TYPE', choices: ['none', 'patch', 'minor', 'major'])
        booleanParam(name: 'IS_RELEASE', defaultValue: false)
    }
    
    stages {
        stage('Version') {
            steps {
                script {
                    def ver = semver(
                        forceBump: params.BUMP_TYPE != 'none' ? params.BUMP_TYPE : null,
                        forceRelease: params.IS_RELEASE
                    )
                    env.BUILD_VERSION = ver.version
                }
            }
        }
    }
}
```

## Troubleshooting

### Version Not Incrementing

**Problem:** Version stays the same across builds

**Solutions:**

- Check for double-bump prevention (`.semver-state` file)
- Verify bump tokens in commit message
- Check `defaultBump` setting
- Enable `defaultBump: 'patch'` for automatic bumps

### Wrong Version Baseline

**Problem:** Version starts from wrong number

**Solutions:**

- Check git tags match `tagPattern`
- Verify `version.txt` contains correct baseline
- Use `strictTagBaseline` to force tag-only
- Check `strategy` setting

### Version Jumps Unexpectedly

**Problem:** Version jumps by more than expected

**Solutions:**

- Check `cumulativePatch` setting
- Verify commit count is correct
- Review git history for unexpected commits
- Disable `cumulativePatch` for predictable bumps

## See Also

- [release](release.md) - Create GitHub releases with semver integration
- [generateChangelog](generateChangelog.md) - Generate changelog with version numbers
- [shouldBuild](shouldBuild.md) - Token-based build filtering
