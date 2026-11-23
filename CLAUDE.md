# CLAUDE.md

Guidance for Claude Code in this repository: **Taihei Tsuji** (`dev.taihei::taihei-tsuji` and the
JVM-only `dev.taihei::taihei-tsuji-tools`; taihei-i18n up to 4.x, on branch `4.0.x`): languages,
typed message families with per-language catalogues, ICU MessageFormat 2.0 and locale-aware
formatting for Scala 3 on the JVM and Scala.js. Part of the Taihei Framework; the family's rules
come from the parent folder's `CLAUDE.md` when this repo is checked out under `~/Projects/Taihei`.

## Commands

```bash
make fix && make fmt   # scalafix, scalafmt: rewrite the sources
make validate          # repo-guard, release-test, check scalafmt and scalafix, compile, test, MiMa and no-zio
make repo-guard        # Coursier's defaults (local Ivy, Central), then REPO_URL for dev.taihei alone;
                       # pins released; resolved dev.taihei files checked
make mima              # against the newest released tag of release.majorLine (on Central, or with an
                       # immutable GitHub Release); a spent tag, with neither, is passed over;
                       # MIMA_BASELINE=x.y.z|none overrides
make test              # ./mill __.test.testForked (JS tests run under Node)
make lint              # ./mill __.checkFormat, then __.fix --check: CI's lint steps
TAIHEI_TEST_JAVA_HOME=<a JDK 17> make test-java-17   # the JVM tests on Java 17, as CI and the release workflow run them
make publish-dry-run   # the bundle Maven Central would receive, written to out/ (a throwaway key in
                       # MILL_PGP_SECRET_BASE64 and MILL_PGP_PASSPHRASE)
```

Only the maintainer releases, through the family's release workflow (ADR 0014): his signed
`v`-tag on `main` or a maintenance branch `M.m.x` starts `.github/workflows/release.yml`, the
caller pinned to a commit of the release repository's `open.yml`, and he approves its one
`release` job. The run builds the tag with `make validate`, `make test-java-17` and `make
publish-dry-run`, so those targets keep their names on every line. A developer commits the
release notes `RELEASE_x_y_z.md` through the merge gate; an agent never creates or pushes a
release tag. The family's Maven repository (`REPO_URL`, the same string as `release.repoUrl` in
build.mill) serves every release from its immutable GitHub Release. Maven Central is frozen; a
security patch of a line whose users are on Central goes there from the same bytes, by nakama's
`make central`. Moving the caller's pin is a reviewed change on `main` and on every maintenance
branch. Credentials never go on make's command line; never read or print `~/.edo-release.env`.

## Layout

- `shared/src/dev/taihei/tsuji/`: `Language`, `MessageFamily` and `MessageDefinition`, the macro
  (`MessageMacros`), `Message`/`Text`/`Displayable`, `MessageArg`, `MessageKey`, `Catalogue`,
  `Catalogues`, `MessageFormat2`, `CoreMessages`; `legacy/` the deprecated 4.x `Message`.
- `shared/resources/META-INF/taihei-tsuji/`: CoreMessages' Dutch and French catalogues (JVM jar).
- `jvm/src/…` and `js/src/…`: the private platform formatters, `ClasspathCatalogues` (JVM) and
  `CatalogueLoader` (Scala.js).
- `tools/src/…`: `taihei-tsuji-tools`, `ExportCatalogues`.
- `zio-json/src/…`: `taihei-tsuji-zio-json` (Mill module `ziojson`, JVM and Scala.js), the zio-json
  givens in `TsujiZioJson`, which write the core's bytes and read through the core; the one
  artifact whose POM may hold `dev.zio` (`make no-zio` checks both sides). Its tests in
  `zio-json/test/src/…` compare it with the core; `make zio-json-newest ZIO_JSON_TEST_VERSION=x.y.z`
  runs them against another zio-json, the newest on Central, before every release.
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
