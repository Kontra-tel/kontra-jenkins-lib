# uploadReleaseAssets

Upload assets (binaries, archives, documentation) to existing GitHub Releases.

## Overview

`uploadReleaseAssets` uploads files to an existing GitHub Release identified by tag. It supports glob patterns, file renaming, overwriting existing assets, and custom content types.

**Key Features:**
- Upload files to existing releases
- Glob pattern support for file matching
- Rename assets during upload
- Overwrite existing assets
- Custom content types
- Multiple file upload
- GitHub App and PAT support

## Basic Usage

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    stages {
        stage('Upload Assets') {
            steps {
                uploadReleaseAssets(
                    tag: 'v1.2.3',
                    credentialsId: 'github-token',
                    assets: 'build/libs/*.jar'
                )
            }
        }
    }
}
```

## Parameters

### Required
- `tag` (String) - GitHub Release tag (e.g., `'v1.2.3'`)
- `credentialsId` (String) - GitHub credentials ID (GitHub App or PAT)

### Asset Configuration
- `assets` (String or List) - Files to upload
  - String: Glob pattern (e.g., `'build/**/*.jar'`)
  - String: Comma-separated list (e.g., `'file1.jar,file2.zip'`)
  - List: Array of file paths (e.g., `['file1.jar', 'file2.zip']`)

### Asset Options
- `assetsRename` (Map) - Rename files: `[sourcePath: newName]`
- `assetOverwrite` (Boolean) - Overwrite existing assets (default: `false`)
- `assetContentType` (String) - MIME type (default: `'application/octet-stream'`)

### GitHub API
- `githubApi` (String) - API endpoint (default: `'https://api.github.com'`)
- `githubUploads` (String) - Upload endpoint (default: `'https://uploads.github.com'`)

## Examples

### Upload Single File

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: 'build/libs/myapp-1.2.3.jar'
        )
    }
}
```

### Upload Multiple Files (Glob)

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: env.BUILD_VERSION,
            credentialsId: 'github-token',
            assets: 'build/libs/*.jar'  // Uploads all JARs
        )
    }
}
```

### Upload Multiple Files (List)

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: [
                'build/libs/app.jar',
                'build/distributions/app.zip',
                'docs/manual.pdf'
            ]
        )
    }
}
```

### Upload Multiple Files (Comma-Separated)

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: 'app.jar,app.zip,manual.pdf'
        )
    }
}
```

### Rename Assets

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: [
                'build/libs/myapp-1.2.3-SNAPSHOT.jar',
                'docs/user-guide-1.2.3.pdf'
            ],
            assetsRename: [
                'build/libs/myapp-1.2.3-SNAPSHOT.jar': 'myapp.jar',
                'docs/user-guide-1.2.3.pdf': 'user-guide.pdf'
            ]
        )
    }
}
```

### Overwrite Existing Assets

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: 'build/libs/app.jar',
            assetOverwrite: true  // Delete and re-upload if exists
        )
    }
}
```

### Custom Content Types

```groovy
stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: 'v1.2.3',
            credentialsId: 'github-token',
            assets: 'docs/api.json',
            assetContentType: 'application/json'
        )
    }
}
```

**Common content types:**
- `application/java-archive` - JAR files
- `application/zip` - ZIP archives
- `application/pdf` - PDF documents
- `application/json` - JSON files
- `text/plain` - Text files
- `application/octet-stream` - Binary files (default)

### Complex Example

```groovy
stage('Upload Assets') {
    steps {
        script {
            def version = env.BUILD_VERSION
            
            uploadReleaseAssets(
                tag: "v${version}",
                credentialsId: 'github-app',
                assets: [
                    "build/libs/myapp-${version}.jar",
                    "build/distributions/myapp-${version}.zip",
                    'docs/manual.pdf',
                    'LICENSE.txt'
                ],
                assetsRename: [
                    "build/libs/myapp-${version}.jar": 'myapp.jar',
                    "build/distributions/myapp-${version}.zip": 'myapp.zip'
                ],
                assetOverwrite: true
            )
        }
    }
}
```

## Asset Detection

The step automatically handles different asset specification formats:

### Glob Patterns

```groovy
assets: 'build/**/*.jar'  // All JARs in build directory (recursive)
assets: 'dist/*.{zip,tar.gz}'  // All ZIPs and tar.gz in dist
assets: '*.exe'  // All executables in workspace root
```

### Comma-Separated

```groovy
assets: 'file1.jar, file2.zip, file3.pdf'
```

### Space-Separated

```groovy
assets: 'file1.jar file2.zip file3.pdf'
```

### List Format

```groovy
assets: ['file1.jar', 'file2.zip', 'file3.pdf']
```

## Content Type Detection

If `assetContentType` is not specified, the step attempts to detect content type from file extension:

| Extension | Detected Content Type |
|-----------|----------------------|
| `.jar` | `application/java-archive` |
| `.zip` | `application/zip` |
| `.tar` | `application/x-tar` |
| `.gz` | `application/gzip` |
| `.pdf` | `application/pdf` |
| `.json` | `application/json` |
| `.txt` | `text/plain` |
| `.md` | `text/markdown` |
| Others | `application/octet-stream` |

## Integration Patterns

### With Release Step

```groovy
@Library('kontra-jenkins-lib') _

pipeline {
    agent any
    
    stages {
        stage('Build') {
            steps {
                sh './gradlew build'
            }
        }
        
        stage('Release') {
            steps {
                script {
                    def ver = semver(writeFile: true)
                    
                    // Create release (without assets)
                    release(
                        version: ver.version,
                        credentialsId: 'github-token',
                        pushTags: true
                    )
                    
                    // Upload assets separately
                    uploadReleaseAssets(
                        tag: "v${ver.version}",
                        credentialsId: 'github-token',
                        assets: 'build/libs/*.jar'
                    )
                }
            }
        }
    }
}
```

### With Release Step (Integrated)

```groovy
stage('Release') {
    steps {
        script {
            // Release step can upload assets directly
            release(
                version: '1.2.3',
                credentialsId: 'github-token',
                assets: 'build/libs/*.jar',  // Calls uploadReleaseAssets internally
                assetOverwrite: true
            )
        }
    }
}
```

### Conditional Upload

```groovy
stage('Upload Assets') {
    when {
        expression { env.IS_RELEASE == 'true' }
    }
    steps {
        uploadReleaseAssets(
            tag: "v${env.BUILD_VERSION}",
            credentialsId: 'github-token',
            assets: 'build/distributions/*'
        )
    }
}
```

### Multi-Platform Builds

```groovy
stage('Build') {
    parallel {
        stage('Linux') {
            steps {
                sh './build-linux.sh'
            }
        }
        stage('Windows') {
            steps {
                sh './build-windows.sh'
            }
        }
        stage('macOS') {
            steps {
                sh './build-macos.sh'
            }
        }
    }
}

stage('Upload') {
    steps {
        uploadReleaseAssets(
            tag: env.RELEASE_TAG,
            credentialsId: 'github-token',
            assets: [
                'dist/myapp-linux-amd64',
                'dist/myapp-windows-amd64.exe',
                'dist/myapp-darwin-amd64'
            ],
            assetsRename: [
                'dist/myapp-linux-amd64': 'myapp-linux',
                'dist/myapp-windows-amd64.exe': 'myapp-windows.exe',
                'dist/myapp-darwin-amd64': 'myapp-macos'
            ]
        )
    }
}
```

## GitHub Credentials

### GitHub App (Recommended)

```groovy
uploadReleaseAssets(
    tag: 'v1.2.3',
    credentialsId: 'github-app-cred',  // GitHub App credential
    assets: 'build/**/*.jar'
)
```

**Setup:**
1. Create GitHub App with Releases permission
2. Install on repository
3. Add credentials to Jenkins
4. Use credential ID

### Personal Access Token

```groovy
uploadReleaseAssets(
    tag: 'v1.2.3',
    credentialsId: 'github-pat',  // String credential with PAT
    assets: 'build/**/*.jar'
)
```

**Setup:**
1. Generate PAT with `repo` scope
2. Add as Jenkins String credential
3. Use credential ID

## Error Handling

The step provides informative error messages:

### Release Not Found

```
uploadReleaseAssets: no release id for tag v1.2.3
```

**Solutions:**
- Verify release exists for tag
- Check tag name matches exactly
- Create release before uploading assets

### No Files Matched

```
uploadReleaseAssets: no files matched ['build/libs/*.jar']
```

**Solutions:**
- Check glob pattern is correct
- Verify files exist in workspace
- Check file paths are relative to workspace root

### Upload Failed

```
uploadReleaseAssets: failed to upload 'myapp.jar' from 'build/libs/myapp.jar'
```

**Solutions:**
- Check network connectivity
- Verify credentials have write permission
- Check file size limits (GitHub max: 2GB per file)
- Enable `assetOverwrite` if asset already exists

### Asset Already Exists

```
uploadReleaseAssets: asset 'myapp.jar' exists; skipping (set assetOverwrite:true to replace)
```

**Solutions:**
- Set `assetOverwrite: true` to replace
- Delete asset manually from GitHub Release
- Use different asset name

## Best Practices

1. **Glob Patterns** - Use glob patterns for flexible file matching
2. **Overwrite Control** - Enable `assetOverwrite` in CI/CD pipelines
3. **Rename for Clarity** - Rename assets to remove version numbers for latest downloads
4. **Content Types** - Specify content types for better browser handling
5. **Separate Upload** - Upload assets after release creation for better error handling
6. **Archive Artifacts** - Archive Jenkins artifacts before uploading

## Limitations

- Maximum file size: 2GB per asset (GitHub limit)
- Maximum number of assets: No hard limit, but practical limit ~100 assets
- Must have existing release (use `release` step to create)
- Requires GitHub credentials with Releases permission

## Troubleshooting

### Cannot Detect Owner/Repo

**Problem:** "cannot detect owner/repo"

**Solutions:**
- Ensure running in git repository
- Verify git remote origin URL is set
- Check origin URL contains github.com

### No GitHub Token

**Problem:** "no GitHub token"

**Solutions:**
- Verify `credentialsId` matches credential ID in Jenkins
- Check credential type (GitHub App or String)
- Test credential binding manually

### Permission Denied

**Problem:** Upload fails with 403 or 401 error

**Solutions:**
- Verify credentials have `repo` scope (PAT)
- Check GitHub App has Releases write permission
- Ensure app is installed on repository

## See Also

- [release](release.md) - Create releases (can upload assets directly)
- [semver](semver.md) - Version management for release tags
