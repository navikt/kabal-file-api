# kabal-file-api
API for file handling before journalføring.

## Code style (ktlint)

The project uses [ktlint](https://ktlint.github.io/ktlint/) via
[ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle). The rules are
configured in `.editorconfig` (`ktlint_code_style = ktlint_official`).

`ktlintCheck` is wired into the Gradle `check` task, which means it runs
automatically as part of `./gradlew build`. The PR build in GitHub Actions runs
`ktlintCheck` as a separate step before test and build, so a PR cannot be merged
until the code style is clean. On failure the report is uploaded as the
`ktlint-report` artifact.

### Commands

```bash
./gradlew ktlintCheck    # check code style (fails on violations)
./gradlew ktlintFormat   # fix everything that can be fixed automatically
```

Reports are written to `build/reports/ktlint/`.

### Formatting from IntelliJ

Pick one of the approaches below:

1. **Gradle task with a keyboard shortcut (simplest, matches CI exactly)**
   - Open the Gradle panel: `Tasks → formatting → ktlintFormat`.
   - Right-click the task and choose *Assign Shortcut* to bind it to e.g. `Ctrl+Alt+K`.
   - Alternatively, right-click and choose *Run* whenever you want to format.

2. **Built-in formatting via `.editorconfig`**
   - IntelliJ reads `.editorconfig` automatically. Verify that it is enabled under
     *Settings → Editor → Code Style → Enable EditorConfig support*.
   - `Ctrl+Alt+L` (*Reformat Code*) then formats according to the same rules.
   - Note: IntelliJ's formatter does not cover every ktlint rule, so run
     `ktlintFormat` before you push.
   - `.editorconfig` disables IntelliJ's wildcard imports
     (`ij_kotlin_name_count_to_use_star_import`). Without it, *Optimize Imports*
     collapses imports into `.*` after five classes from the same package, which
     trips ktlint's `no-wildcard-imports` rule. If a file already has `.*`, delete
     the star import and press `Ctrl+Alt+O` to get explicit imports back.

3. **Ktlint plugin (format on save)**
   - Install the *Ktlint* plugin from the JetBrains Marketplace.
   - Under *Settings → Tools → KtLint*, select mode `Distract free` for automatic
     formatting on save. The plugin picks up `.editorconfig` on its own.

### Optional: git pre-commit hook

```bash
./gradlew addKtlintFormatGitPreCommitHook   # format changed files before commit
# or
./gradlew addKtlintCheckGitPreCommitHook    # block the commit on violations
```

## Static analysis (detekt)

[detekt](https://detekt.dev/) is deliberately scoped to a **single rule**:
`NamedArguments`. Formatting is owned by ktlint, so detekt's formatting ruleset
is not on the classpath and every other ruleset is switched off explicitly in
`config/detekt/detekt.yml`.

`NamedArguments` reports calls with more than one positional argument, where
argument order is an easy thing to get wrong:

```kotlin
// reported
shrinkFactor(image.width, image.height)

// accepted
shrinkFactor(width = image.width, height = image.height)
```

Symmetric stdlib maths are exempt via `ignoreMethods`, since `max(a = 1, b = n)`
is noise rather than documentation. Calls to Java methods are skipped
automatically, because Kotlin cannot name their arguments.

### Commands

```bash
./gradlew detektMain detektTest   # analyse main and test sources
```

Reports are written to `build/reports/detekt/`.

### Why `detektMain` and not `detekt`

`NamedArguments` implements `RequiresAnalysisApi`: detekt has to resolve the
callee to know the parameter names. Only the `detektMain` and `detektTest` tasks
run with a compile classpath. The plain `detekt` task would find nothing and pass
silently, so it is disabled in `build.gradle.kts`. Both analysis aware tasks are
wired into `check`, and therefore run as part of `./gradlew build`.

### A note on the detekt version

The project uses detekt `2.0.0-alpha.6`, which is a **prerelease**. This is a
deliberate choice: the latest stable release, 1.23.8, is compiled against Kotlin
2.0.21 and refuses to run against this project's Kotlin 2.4.10 without pinning
`kotlin-compiler-embeddable` to an older version on the detekt classpath. detekt
2.x is built against Kotlin 2.4 and needs no such workaround.

Expect breaking changes in config keys between alpha releases. `allowedArguments`
for instance was named `threshold` in 1.x.
