# CLAUDE.md

Guidance for Claude Code in this repository: **Taihei Tsuji** (`dev.taihei::taihei-tsuji` and the
JVM-only `dev.taihei::taihei-tsuji-tools`; taihei-i18n up to 4.x, on branch `4.0.x`): languages,
typed message families with per-language catalogues, ICU MessageFormat 2.0 and locale-aware
formatting for Scala 3 on the JVM and Scala.js. Part of the Taihei Framework; the family's rules
come from the parent folder's `CLAUDE.md` when this repo is checked out under `~/Projects/Taihei`.

## Commands

```bash
make validate          # repo-guard, release-test, scalafix, scalafmt, both checked as CI does, compile, test, MiMa and no-zio
make repo-guard        # Coursier's defaults (local Ivy, Central), then REPO_URL for dev.taihei alone;
                       # pins released; resolved dev.taihei files checked
make mima              # against the newest tag of release.majorLine (6.x), which must be on Central or REPO_URL
                       # (between a tag push and publish-repo it fails; MIMA_BASELINE=x.y.z)
make test              # ./mill __.test.testForked (JS tests run under Node)
make lint              # ./mill __.checkFormat, then __.fix --check: CI's lint steps
TAIHEI_TEST_JAVA_HOME=<a JDK 17> ./mill tsuji.jvm.test.testForked + tsuji.tools.test.testForked + ziojson.jvm.test.testForked   # the JVM tests on Java 17, as CI runs them
make publish-dry-run   # the bundle Maven Central would receive, written to out/
```

Only the maintainer releases. Until the family's release workflow takes over (ADR 0009), he
does it on the interim route of ADR 0009 section 11 (the one signed bundle of ADR 0004) at his
own terminal, as the README's Releasing section lists: `release-prepare TYPE=… EMBARGO=0|1 TICKET=<key>` (EMBARGO=1 when the ticket has the label
embargo), `release-finalize` (the signed bundle, tag local), the push, `release-github` and
`publish-repo`. An agent never runs these, nor creates or pushes a release tag; `publish-dry-run`
and `release-test` upload nothing and stay open to it. The family's Maven repository
(`REPO_URL`, the same string as `release.repoUrl` in build.mill) serves every release from its
immutable GitHub Release. Maven Central is frozen: no version of this line goes there, and a
security fix for a line whose users are on Central is released from its maintenance branch. The
machinery is shared with mihon: keep everything but the per-library lines identical. Credentials
never go on make's command line and are not exported in the shell you build in; never read or
print `~/.edo-release.env`.

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
- `golden/`: the corpus and the generators of the golden files (`Golden502`), which pin the wire
  bytes and the exported catalogues of 5.0.2 per platform; not published.
- `scripts/`: the release machinery's Python and its tests (`release.py`, `fake_servers.py`,
  `test-release.sh`, `test-mill-launcher`), shared with mihon.
- `keys/release-key.asc`: the public half of the release key.
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
