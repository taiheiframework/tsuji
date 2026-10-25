# CLAUDE.md

Guidance for Claude Code in this repository: **Taihei Tsuji** (`dev.taihei::taihei-tsuji` and the
JVM-only `dev.taihei::taihei-tsuji-tools`; taihei-i18n up to 4.x, on branch `4.0.x`): languages,
typed message families with per-language catalogues, ICU MessageFormat 2.0 and locale-aware
formatting for Scala 3 on the JVM and Scala.js. Part of the Taihei Framework; the family's rules
come from the parent folder's `CLAUDE.md` when this repo is checked out under `~/Projects/Taihei`.

## Commands

```bash
make validate          # repo-guard, release-test, scalafix, scalafmt, compile, test and MiMa
make repo-guard        # Coursier's defaults (local Ivy, Central), then REPO_URL for dev.taihei alone;
                       # pins released; resolved dev.taihei files checked
make mima              # against the newest 5.x tag, which must be on Central or REPO_URL
                       # (between a tag push and publish-repo it fails; MIMA_BASELINE=x.y.z)
make test              # ./mill __.test.testForked (JS tests run under Node)
TAIHEI_TEST_JAVA_HOME=<a JDK 17> ./mill tsuji.jvm.test.testForked   # the JVM tests on Java 17
make publish-dry-run   # the bundle Maven Central would receive, written to out/
make release-prepare TYPE=patch|minor|major EMBARGO=0|1   # then review RELEASE_x_y_z.md;
                       # EMBARGO=1 when the ticket has the label embargo (read it with fude first)
make release-finalize  # the signed bundle and its checks, tag local: only with the maintainer's yes
make release-github    # after the push: the immutable GitHub Release, which publishes the version
make publish-repo      # the family repository's deploy, then verify-repo reads every file back
```

Releases follow ADR 0004: one signed bundle, built locally by release-finalize (nothing is
uploaded), then main and the tag are pushed, release-github keeps it on an immutable GitHub
Release, the family's Maven repository (`REPO_URL`, the same string as `release.repoUrl` in
build.mill) serves it from there, and the next release train in nakama takes it to Maven Central.
A security fix (`EMBARGO=1`) goes to Central first with `make publish`, and gets its GitHub
Release from Central's files (`make central-bundle`) after its advisory. The machinery is shared
with mihon: keep everything but the per-library lines identical. Credentials never go on make's
command line; never read or print `~/.edo-release.env`.

## Layout

- `shared/src/dev/taihei/tsuji/`: `Language`, `MessageFamily` and `MessageDefinition`, the macro
  (`MessageMacros`), `Message`/`Text`/`Displayable`, `MessageArg`, `MessageKey`, `Catalogue`,
  `Catalogues`, `MessageFormat2`, `CoreMessages`; `legacy/` the deprecated 4.x `Message`.
- `shared/resources/META-INF/taihei-tsuji/`: CoreMessages' Dutch and French catalogues (JVM jar).
- `jvm/src/…` and `js/src/…`: the private platform formatters, `ClasspathCatalogues` (JVM) and
  `CatalogueLoader` (Scala.js).
- `tools/src/…`: `taihei-tsuji-tools`, `ExportCatalogues`.
- `shared/test/src/…`: zio-test specs, run on both platforms; `jvm/test` and `js/test` for what
  exists on one platform only.
- `docs/skill/`: the API reference for the library's Claude Code skill.

## Rules

- Whatever is in `shared/` compiles on Scala.js: no JVM-only API there.
- Java 17 is the JVM floor: the JVM modules compile with `-java-output-version:17`, and CI runs
  the JVM tests on Java 17 too.
- Catalogue files and JSON messages are input the application did not compile: bound sizes and
  nesting, never throw, test with adversarial input.
- MiMa compares within the current major line (`release.majorLine` in `build.mill`).
- The public API follows semantic versioning; a break is a major release with a migration section
  in the README.
- Every dependency comes from Maven Central under an Apache-compatible licence (a dev.taihei
  version not on Central yet from `REPO_URL`); the build needs no credentials.
- Work is tracked in Jira project TAIHEI; commits are `TAIHEI-12: subject` with a body that explains the
  change to a reader without access to the ticket.
