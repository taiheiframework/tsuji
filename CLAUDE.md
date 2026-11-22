# CLAUDE.md

Guidance for Claude Code in this repository: **Taihei Framework i18n** (`dev.taihei::taihei-i18n`),
languages, translatable messages, ICU MessageFormat 2.0 and locale-aware formatting for Scala 3 on
the JVM and Scala.js. Part of the Taihei Framework; the family's rules come from the parent folder's
`CLAUDE.md` when this repo is checked out under `~/Projects/Taihei`.

## Commands

```bash
make validate          # scalafix, scalafmt, compile, test and MiMa, JVM and Scala.js
make mima              # against the newest 4.x release on Maven Central; a spent tag (no GitHub
                       # Release) is passed over; a released one not on Central yet fails
make test              # ./mill __.test.testForked (JS tests run under Node)
TAIHEI_TEST_JAVA_HOME=<a JDK 17> make test-java-17   # the JVM tests on Java 17, as the release workflow runs them
make publish-dry-run   # the bundle Maven Central would receive, written to out/ (a throwaway key in
                       # MILL_PGP_SECRET_BASE64 and MILL_PGP_PASSPHRASE)
```

Only the maintainer releases, through the family's release workflow (ADR 0014), where taihei-i18n
4.x is a family entry of its own: his signed `v4.0.z` tag on this branch starts
`.github/workflows/release.yml`, the caller pinned to a commit of the release repository's
`open.yml`, and he approves its one `release` job. The run builds the tag with `make validate`,
`make test-java-17` and `make publish-dry-run`, so those targets keep their names. A developer
commits the release notes `RELEASE_4_0_z.md` through the merge gate; an agent never creates or
pushes a release tag. A security patch goes to Maven Central afterwards, from the same bytes, by
nakama's `make central`. Moving the caller's pin is a reviewed change here as on `main`.

## Layout

- `shared/src/dev/taihei/i18n/`: `Language`, `Message`, `MessageFormat2`, `CoreMessages`.
- `jvm/src/…` and `js/src/…`: the platform formatters behind `PlatformNumberFormatter`,
  `PlatformDateTimeFormatter`, `PlatformLocales`, `PlatformLanguageNames`.
- `shared/test/src/…`: zio-test specs, run on both platforms.
- `docs/skill/`: the API reference for the library's Claude Code skill.

## Rules

- Whatever is in `shared/` compiles on Scala.js: no JVM-only API there.
- The public API follows semantic versioning; a break is a major release with a migration section
  in the README.
- Every dependency comes from Maven Central under an Apache-compatible licence; the build needs no
  credentials.
- Work is tracked in Jira project TAIHEI; commits are `TAIHEI-12: subject` with a body that explains the
  change to a reader without access to the ticket.
