# generateChangelog

Generate formatted changelogs from git commits with support for both Markdown and plain text output.

## Overview

`generateChangelog` automatically generates changelogs from your git commit history, grouping commits by type (Features, Bug Fixes, Documentation, etc.) and providing both Markdown and plain text output formats.

**Key Features:**
- Dual output formats (Markdown with links + plain text)
- Commit grouping by type (Features, Bug Fixes, Documentation, etc.)
- Automatic token removal from commit messages
- Multi-line commit message support
- Configurable versioning and date ranges
- Conventional commits support
- GitHub repository link integration

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Generate Changelog') {
            steps {
                script {
                    generateChangelog(
                        version: env.BUILD_VERSION,
                        outputFile: 'CHANGELOG.md',
                        plainOutput: 'CHANGELOG.txt'
                    )
                }
            }
        }
    }
}
```

## Parameters

### Output Files
- `outputFile` (String) - Markdown output file (default: `'CHANGELOG.md'`)
- `plainOutput` (String) - Plain text output file (optional, e.g., `'CHANGELOG.txt'`)
- `appendTo` (String) - Deprecated alias for `copyTo` (use `copyTo` instead)

### Versioning
- `version` (String) - Version for this changelog section (default: `env.BUILD_VERSION` or `'Unversioned'`)
- `title` (String) - Main title for new changelog (default: `'# Changelog'`)
- `resetToken` (String) - Token to reset/recreate changelog (default: `'!resetLog'`)

### Git Range
- `since` (String) - Starting commit/tag for changelog range (auto-detected if not provided)
- `tagPattern` (String) - Pattern for git tags (default: `'v[0-9]*'`)
- `maxCommits` (Integer) - Limit number of commits (default: `0` = no limit)

## Output Formats

### Markdown Output

The Markdown output includes:
- Version headers with timestamps
- Grouped commits by type (Features, Bug Fixes, etc.)
- Clickable commit hash links to GitHub
- Author attribution
- Section separators

**Example:**

```markdown
## Version 1.2.3 (2024-01-15 10:30)

### Features
- **feat:** Add user authentication ([a1b2c3d](https://github.com/owner/repo/commit/a1b2c3d)) - John Doe
- **feat:** Implement dark mode ([e4f5g6h](https://github.com/owner/repo/commit/e4f5g6h)) - Jane Smith

### Bug Fixes
- **fix:** Correct login validation ([i7j8k9l](https://github.com/owner/repo/commit/i7j8k9l)) - John Doe

### Documentation
- **docs:** Update API documentation ([m0n1o2p](https://github.com/owner/repo/commit/m0n1o2p)) - Jane Smith

**Contributors:** John Doe, Jane Smith

---
```

### Plain Text Output

The plain text output provides:
- ASCII separators instead of markdown
- Bracketed commit hashes instead of links
- No markdown formatting
- Clean, printable format

**Example:**

```
===================================================================
Version 1.2.3 (2024-01-15 10:30)
===================================================================

Features:
-------------------------------------------------------------------
feat: Add user authentication [a1b2c3d] - John Doe
feat: Implement dark mode [e4f5g6h] - Jane Smith

Bug Fixes:
-------------------------------------------------------------------
fix: Correct login validation [i7j8k9l] - John Doe

Documentation:
-------------------------------------------------------------------
docs: Update API documentation [m0n1o2p] - Jane Smith

Contributors: John Doe, Jane Smith

===================================================================
```

## Commit Grouping

Commits are automatically grouped into categories based on conventional commit prefixes and keywords:

### Categories

1. **Features** - `feat:`, `feature:`, `add:`, `new:`
2. **Bug Fixes** - `fix:`, `bugfix:`, `bug:`, `hotfix:`, `patch:`
3. **Documentation** - `docs:`, `doc:`, `documentation:`
4. **Performance** - `perf:`, `performance:`, `optimize:`, `optimization:`
5. **Refactoring** - `refactor:`, `refactoring:`, `cleanup:`
6. **Other** - Everything else

## Token Removal

The following tokens are automatically removed from commit messages:

- `!release` - Release marker
- `!tag` - Tag marker
- `!major` - Major version bump
- `!minor` - Minor version bump
- `!patch` - Patch version bump
- `!resetLog` - Changelog reset (configurable via `resetToken`)
- `!nobuild` - No build marker

## Advanced Examples

### Basic Changelog Generation

```groovy
stage('Changelog') {
    steps {
        script {
            generateChangelog()  // Uses all defaults
        }
    }
}
```

### With Versioning and Plain Text

```groovy
stage('Changelog') {
    steps {
        script {
            def version = semver()  // Get version from semver step
            generateChangelog(
                version: version.version,
                outputFile: 'CHANGELOG.md',
                plainOutput: 'CHANGELOG.txt'
            )
        }
    }
}
```

### Custom Version Range

```groovy
stage('Changelog') {
    steps {
        script {
            generateChangelog(
                since: 'v1.0.0',
                version: '1.1.0',
                title: '# My Project Changelog'
            )
        }
    }
}
```

### Limit Commits

```groovy
stage('Changelog') {
    steps {
        script {
            generateChangelog(
                maxCommits: 50,
                version: env.BUILD_VERSION
            )
        }
    }
}
```

### Reset Changelog

To reset/recreate the changelog from scratch, include the reset token in your commit message:

```bash
git commit -m "Restructure changelog !resetLog"
```

Or force it programmatically:

```groovy
stage('Changelog') {
    steps {
        script {
            // Delete existing changelog
            sh 'rm -f CHANGELOG.md'
            
            generateChangelog(
                version: '2.0.0',
                title: '# Changelog - v2.0 Release'
            )
        }
    }
}
```

## Integration with Release

Use `generateChangelog` to create enhanced GitHub release notes:

```groovy
stage('Release') {
    steps {
        script {
            def semverResult = semver(
                writeFile: true,
                createTag: true
            )
            
            release(
                tag: semverResult.version,
                name: "Release ${semverResult.version}",
                credentialsId: 'github-token',
                useChangelogModule: true,  // Use generateChangelog
                changelogVersion: semverResult.version
            )
        }
    }
}
```

For more details on enhanced release notes, see [ENHANCED_RELEASE_NOTES.md](ENHANCED_RELEASE_NOTES.md).

## Multi-line Commit Messages

The step properly handles multi-line commit messages:

**Commit:**
```
feat: Add new authentication system

This commit introduces a new authentication system with:
- OAuth2 support
- Multi-factor authentication
- Session management
```

**Output:**
```markdown
### Features
- **feat:** Add new authentication system ([abc1234](https://github.com/owner/repo/commit/abc1234)) - John Doe
  This commit introduces a new authentication system with:
  - OAuth2 support
  - Multi-factor authentication
  - Session management
```

## Git Range Detection

If `since` is not provided, the step automatically determines the range:

1. Check `env.GIT_PREVIOUS_SUCCESSFUL_COMMIT`
2. Check `env.GIT_PREVIOUS_COMMIT`
3. Find latest git tag matching `tagPattern`
4. Fallback to `HEAD~50..HEAD`

## Environment Variables

The step uses and sets these environment variables:

- `GIT_URL` - Repository URL (for generating links)
- `GIT_PREVIOUS_SUCCESSFUL_COMMIT` - Previous successful commit (Jenkins)
- `GIT_PREVIOUS_COMMIT` - Previous commit (Jenkins)
- `BUILD_VERSION` - Default version if not specified
- `COMMIT_MESSAGE` - Used to check for `resetToken`

## Return Value

Returns the path to the generated Markdown changelog file (value of `outputFile`).

## Best Practices

1. **Use Conventional Commits** - Format commits as `type: description` for automatic grouping
2. **Enable Plain Text** - Provide `plainOutput` for environments that don't support Markdown
3. **Version Consistently** - Use `semver()` result for version parameter
4. **Archive Outputs** - Archive both Markdown and plain text files as build artifacts
5. **Reset Carefully** - Use `resetToken` sparingly, as it recreates the entire changelog

## Example Pipeline

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Version') {
            steps {
                script {
                    env.SEMVER_RESULT = semver(
                        writeFile: true,
                        createTag: false
                    )
                }
            }
        }
        
        stage('Build') {
            steps {
                sh './gradlew build'
            }
        }
        
        stage('Changelog') {
            steps {
                script {
                    def version = readJSON(text: env.SEMVER_RESULT).version
                    generateChangelog(
                        version: version,
                        outputFile: 'CHANGELOG.md',
                        plainOutput: 'CHANGELOG.txt',
                        maxCommits: 100
                    )
                }
            }
        }
        
        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'CHANGELOG.*', allowEmptyArchive: false
            }
        }
    }
}
```

## Troubleshooting

### No Commits Found

**Problem:** "No change entries detected for range: HEAD~50..HEAD"

**Solutions:**
- Check git history has commits in range
- Verify `since` parameter points to valid commit/tag
- Ensure commits aren't merge commits (automatically filtered)
- Check `maxCommits` isn't too restrictive

### Links Not Generated

**Problem:** Commit hashes shown as plain text instead of links

**Solutions:**
- Verify `GIT_URL` environment variable is set
- Check repository URL ends correctly (auto-removes `.git`)
- Ensure running in git repository context

### Tokens Not Removed

**Problem:** Version bump tokens appearing in changelog

**Solutions:**
- Tokens are case-sensitive (e.g., `!major` not `!MAJOR`)
- Check custom `resetToken` if you changed it
- Verify token list in code if using custom tokens

### Grouping Not Working

**Problem:** All commits go to "Other" category

**Solutions:**
- Use conventional commit format: `type: description`
- Check commit message prefix matches known types
- Prefixes are case-insensitive
- Ensure colon (`:`) follows type keyword

## See Also

- [release](release.md) - Create GitHub releases with changelog integration
- [semver](semver.md) - Semantic versioning for changelog versions
- [ENHANCED_RELEASE_NOTES](ENHANCED_RELEASE_NOTES.md) - Detailed guide for enhanced release notes
