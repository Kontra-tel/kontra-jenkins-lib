# Enhanced Release Notes with generateChangelog

## Overview

The `release` module can now optionally use the `generateChangelog` module to create much better formatted GitHub release notes.

## Comparison

### Before (Simple Commit List)

```
Changes since last release: (v1.2.3 → v1.2.4)

- Fix bug in parser (abc1234)
- Add new feature (def5678)
- Update documentation !release (ghi9012)
```

**Limitations:**
- Only commit subject line (no body)
- No grouping
- Includes tokens like `!release`, `!tag`
- No multi-line message support

### After (Enhanced with generateChangelog)

```
Changes since last release: (v1.2.3 → v1.2.4)

### Features
- **Add new feature** ([def5678](url))
  - Includes caching layer
  - Adds user preferences

### Bug Fixes
- **Fix memory leak in parser** ([abc1234](url))

### Documentation
- **Update documentation** ([ghi9012](url))

---
**Contributors:** John Doe, Jane Smith
===
```

**Benefits:**
- ✅ Full commit messages (subject + body)
- ✅ Grouped by type (Features, Bug Fixes, Documentation, etc.)
- ✅ Tokens automatically removed (!release, !tag, etc.)
- ✅ Multi-line commits with sub-bullets
- ✅ Bold formatting for better readability
- ✅ Clickable commit links
- ✅ Contributors list

## Usage

### Enable Enhanced Release Notes

Add `useChangelogModule: true` to your release call:

```groovy
release([
    version: '1.2.4',
    credentialsId: 'github-token',
    
    // Enable enhanced release notes
    useChangelogModule: true,
    
    // Optional: customize header
    releaseNotesHeader: 'What changed in this release:',
    
    // Other params...
    pushTags: true,
    createGithubRelease: true
])
```

### Pipeline Integration

Update your Jenkins pipeline:

```groovy
stage('Release') {
    steps {
        script {
            release(
                version: env.BUILD_VERSION,
                credentialsId: params.GH_CREDENTIALS_ID,
                
                // Enhanced notes with grouping and full messages
                useChangelogModule: true,
                attachCommitNotes: true,  // Must be true to include notes
                generateReleaseNotes: false,  // Don't use GitHub auto-gen
                
                // Standard params
                pushTags: true,
                createGithubRelease: true,
                releaseDraft: false,
                prerelease: false
            )
        }
    }
}
```

### Add Pipeline Parameter

Add this to your pipeline parameters to let users toggle:

```groovy
parameters {
    booleanParam(
        name: 'USE_ENHANCED_NOTES',
        defaultValue: true,
        description: 'Use enhanced changelog module for release notes'
    )
    // ... other params
}
```

Then in your release call:

```groovy
release(
    version: env.BUILD_VERSION,
    useChangelogModule: params.USE_ENHANCED_NOTES,
    // ... other params
)
```

## How It Works

1. **Generates temporary changelog** using `generateChangelog` module
2. **Extracts content** (removes version header to avoid duplication)
3. **Adds custom header** with version range (e.g., "Changes since v1.2.3")
4. **Sends to GitHub** as release body
5. **Cleans up** temporary file

## Fallback Behavior

If `generateChangelog` fails for any reason, it automatically falls back to the simple commit list:

```groovy
try {
    // Use enhanced changelog
} catch (Exception e) {
    echo "Failed to generate changelog, falling back to simple commit list"
    // Falls back to: git log --no-merges --pretty='- %s (%h)'
}
```

This ensures releases always succeed even if there's an issue with the changelog module.

## Configuration Options

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `useChangelogModule` | boolean | `false` | Enable enhanced release notes |
| `attachCommitNotes` | boolean | `true` | Include commit notes in release body |
| `releaseNotesHeader` | string | `'Changes since last release:'` | Header text for notes section |
| `generateReleaseNotes` | boolean | `false` | Use GitHub auto-gen (conflicts with custom notes) |

## Important Notes

### Mutual Exclusivity

You cannot use both custom notes and GitHub auto-generation:

```groovy
// ❌ WRONG - conflicting options
release(
    useChangelogModule: true,      // Custom notes
    generateReleaseNotes: true     // GitHub auto-gen
)

// ✅ CORRECT - choose one
release(
    useChangelogModule: true,      // Use enhanced custom notes
    generateReleaseNotes: false
)

// ✅ OR
release(
    attachCommitNotes: false,      // No custom notes
    generateReleaseNotes: true     // Use GitHub auto-gen
)
```

### Token Removal

The enhanced notes automatically remove these tokens:
- `!release`, `!tag`, `!deploy`
- `!major`, `!minor`, `!patch`
- `!resetLog`

### Commit Grouping

Commits are automatically categorized using:
- **Conventional Commits**: `feat:`, `fix:`, `docs:`, `perf:`, `refactor:`
- **Keywords**: add, new, fix, bug, doc, performance, cleanup, etc.

## Example Output

Here's what a real GitHub release would look like:

---

## v1.2.4

**Released:** 2025-10-04 14:30:00 UTC

Changes since last release: (v1.2.3 → v1.2.4)

### Features
- **Add user authentication module** ([abc1234](https://github.com/user/repo/commit/abc1234))
  - Supports OAuth and SAML
  - Includes session management
- **Implement caching layer** ([def5678](https://github.com/user/repo/commit/def5678))

### Bug Fixes
- **Fix memory leak in parser** ([ghi9012](https://github.com/user/repo/commit/ghi9012))
  - Properly dispose of resources
  - Add unit tests for edge cases

### Documentation
- **Update installation guide** ([jkl3456](https://github.com/user/repo/commit/jkl3456))

---
**Contributors:** John Doe, Jane Smith, Bob Johnson
===

---

## Troubleshooting

### Notes not appearing
- Ensure `attachCommitNotes: true` (default)
- Check that there are commits since the last tag
- Verify `useChangelogModule: true` is set

### Fallback to simple list
- Check Jenkins console for error message
- Verify `generateChangelog.groovy` exists in `vars/`
- Check that git history is available

### GitHub API errors
- Verify `credentialsId` is valid
- Check GitHub API rate limits
- Ensure token has `repo` scope

## See Also

- [generateChangelog.groovy](../vars/generateChangelog.groovy) - The changelog module
- [release.groovy](../vars/release.groovy) - The release module
- [GitHub Releases API](https://docs.github.com/en/rest/releases)
