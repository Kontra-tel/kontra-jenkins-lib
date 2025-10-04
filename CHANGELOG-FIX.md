# Changelog Generation Fixes

## Issues Fixed

### 1. Multi-line Commit Messages Not Captured
**Problem:** Only the first line (subject) of commit messages was captured using `%s` format.

**Solution:** Changed to `%B` format which captures the full commit message including body.

```groovy
// Before
String fmt = "%H%x1f%an%x1f%s%x1e"  // %s = subject only

// After  
String fmt = "%H%x1f%an%x1f%B%x1e"  // %B = full message
```

### 2. Multi-line Messages Not Formatted Properly
**Problem:** Multi-line messages weren't formatted nicely in the changelog.

**Solution:** Added proper markdown formatting with indentation for additional lines:

```markdown
- First line of commit message (abc1234)
  Additional line with details
  - Bullet points work
  More details
```

### 3. Tokens (!release, !tag, etc.) Appearing in Changelog
**Problem:** Command tokens like `!release`, `!major`, `!minor` were appearing in changelog entries.

**Solution:** Added `cleanCommitMessage()` function that removes all tokens:
- `!release`
- `!tag`
- `!deploy`
- `!major`
- `!minor`
- `!patch`
- `!resetLog`

All removals are case-insensitive.

## Changes Made

### vars/generateChangelog.groovy

1. **Changed git log format** (Line ~27):
   - From `%s` (subject only) to `%B` (full message)

2. **Added message cleaning** (Line ~42):
   ```groovy
   String message = cleanCommitMessage(parts[2])
   if (message) {  // Only add if there's content after cleaning
       commits.add([hash: parts[0], author: parts[1], message: message])
   }
   ```

3. **Improved message formatting** (Lines ~69-84):
   - First line as main bullet point
   - Additional lines indented with 2 spaces
   - Empty lines are filtered out

4. **Added helper function** (Lines ~106-125):
   ```groovy
   private String cleanCommitMessage(String msg) {
       // Removes all tokens and cleans whitespace
   }
   ```

## Example Output

### Before
```markdown
## v1.2.3 (2025-10-04)
- Add new feature !release (abc1234)
```

### After
```markdown
## v1.2.3 (2025-10-04)
- Add new feature (abc1234)
  This feature includes:
  - Support for multi-line messages
  - Better formatting
  - Token removal
```

## Testing

All existing tests pass:
```
BUILD SUCCESSFUL in 6s
17/17 tests passing
```

No breaking changes - the function signature and return values remain the same.
