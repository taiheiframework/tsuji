# Taihei Framework i18n

A composable, type-safe internationalization library for Scala 3 — locales,
translatable messages, ICU MessageFormat 2.0, and locale-aware number/date
formatting. Cross-compiled for the **JVM and Scala.js**.

Part of the [Taihei Framework](https://taihei.dev), the open-source Scala 3
libraries from Innovenso. The package is `dev.taihei.i18n`.

## Modules

Published on Maven Central under `dev.taihei`:

| Artifact                | Platform | Purpose                                              |
| ----------------------- | -------- | ---------------------------------------------------- |
| `taihei-i18n`           | JVM      | The full library on `java.time` / `java.text`.       |
| `taihei-i18n_sjs1`      | Scala.js | The same API, formatting via the browser `Intl` API. |

```scala
mvn"dev.taihei::taihei-i18n:4.0.2"     // Mill, JVM
mvn"dev.taihei::taihei-i18n::4.0.2"    // Mill, Scala.js
"dev.taihei" %%% "taihei-i18n" % "4.0.2" // sbt
```

The `shared/` sources compile on both platforms; `jvm/` and `js/` hold only the
platform-specific number/date formatters.

## Stack

- Mill 1.3.1
- Scala 3.9.0 (LTS) · Scala.js 1.22.0
- ZIO 2.1.27 (`zio-json` 1.1 for codecs, `zio-test`)
- scala-java-time 2.8 (JS `java.time` support)

Everything resolves from Maven Central; the build needs no credentials.

## Build

```sh
make compile        # ./mill __.compile           (JVM + JS)
make test           # ./mill __.test.testForked    (JVM + JS; JS runs on Node)
make fmt            # ./mill __.reformat
make fix            # ./mill __.fix                 (scalafix)
make validate       # fix + fmt + compile + test + mima
make mima           # binary compatibility with the newest release on Maven Central; a spent
                    # tag, with no GitHub Release, is passed over (MIMA_BASELINE=x.y.z|none)
make test-java-17   # the JVM tests on Java 17 (TAIHEI_TEST_JAVA_HOME=<a JDK 17>)
make publishLocal   # to ~/.ivy2/local (a SNAPSHOT only)
make publish-dry-run  # the bundle Maven Central would receive, written to out/; needs a
                      # throwaway signing key in MILL_PGP_SECRET_BASE64 and MILL_PGP_PASSPHRASE
```

## Releasing

The version is the git tag: an exact `v`-tag on a clean tree is the release,
anything else a SNAPSHOT of the last tag.

A 4.x version is released from the branch `4.0.x` by the Taihei Framework's
[release workflow](https://github.com/taiheiframework/release), in two acts of the maintainer:
he signs an annotated tag `v4.0.z` on a commit of `4.0.x` that holds the version's notes
`RELEASE_4_0_z.md` and pushes it, and he approves the run's one `release` job. The tag starts
`.github/workflows/release.yml`, which calls the release workflow at a pinned commit. It checks the
tag's signature, builds a fresh checkout of the tag with no secret (`make validate`, `make
test-java-17`, `make publish-dry-run`), and only then, in a job that runs no code of this
repository, signs every jar and POM, keeps the bundle on an immutable GitHub Release of the tag and
publishes its files to the family's Maven repository. 4.x users are on Maven Central, so a
security patch goes there afterwards, from the same bytes. A tag whose run fails before the GitHub
Release is published is spent: the next release takes the next number, and MiMa passes over it.

Releases are signed by the Taihei Framework release key, fingerprint
`5026 B299 6DDB 697E C463  0BCF 14A7 33E2 A507 702F`, with the user ids
`Taihei Framework Releases <releases@taihei.dev>` and, from before the family was renamed,
`Edo Framework Releases <releases@edoframework.com>`. The fingerprint is what to verify. The
key is published on keyserver.ubuntu.com and keys.openpgp.org.

## Coming from edo-i18n 3.0.0 or genius-i18n

From 4.0.0 on, the library has the API of `com.edoframework::edo-i18n:3.0.0` under its new name:

- the dependency is `dev.taihei::taihei-i18n`;
- the package is `dev.taihei.i18n`: replace `com.edoframework.i18n` in your imports.

Coming from `fish.genius::genius-i18n:2.0.0`, replace `genius.i18n` instead. 3.0.0 also
moved to Scala 3.9 (LTS), so your build needs Scala 3.9 or later, to `zio-json` 1.1
instead of 0.9, and to the Apache License 2.0.

## Quick start

```scala
import dev.taihei.i18n.*

// A translatable message with named placeholders:
val greeting = Message.withNamedArgs(
  Language.English -> "Hello {$name}, you have {$points} points",
  Language.Dutch   -> "Hallo {$name}, u hebt {$points} punten",
  Language.French  -> "Bonjour {$name}, vous avez {$points} points",
)("name" -> "Alice", "points" -> 150)

greeting.translate(Language.English)  // Hello Alice, you have 150 points
greeting.translate(Language.French)   // Bonjour Alice, vous avez 150 points
```

`Language` is identified by its BCP 47 tag. `English`, `Dutch` and `French` are
built in; any other language is `Language("de")` or `Language("nl-BE")`, and
`Language.of(tag)` parses a tag, falling back to English only for invalid input. A
message without a translation for a language falls back to its base language
(`nl-BE` to `nl`), then to English; numbers and dates format in the language's
locale. Both `Language` and `Message` have `zio-json` codecs, so messages serialize
cleanly across a wire (a built-in as `"English"`, any other language as its tag).

Since 2.0.0 `Language` is a class, not an enum: `case English =>` still matches,
but a `match` on a language needs a `case _`, and `ordinal`/`fromOrdinal` are gone.
`isoCode` is the full tag, so it is `"nl-BE"` for Belgian Dutch: compare
`base.isoCode` where only the language matters. `Language.of` reads tags, not the
names `"English"`/`"Dutch"`/`"French"` (that is `valueOf`).

### ICU MessageFormat 2.0

```scala
val tmpl =
  """.match {$count :plural}
    |when one   {You have one item}
    |when other {You have {$count} items}""".stripMargin

MessageFormat2.format(tmpl, Map("count" -> 1), "en")  // You have one item
MessageFormat2.format(tmpl, Map("count" -> 5), "en")  // You have 5 items
```

Placeholders support functions — `{$value :number style=currency}`,
`{$date :datetime dateStyle=long}` — which route through the cross-platform
`PlatformNumberFormatter` / `PlatformDateTimeFormatter`.

### Pre-built messages

`CoreMessages` ships a tri-lingual library of common messages grouped under
`Authentication`, `Authorization`, `Validation`, `Exceptions`, and `General` —
e.g. `CoreMessages.Validation.stringMinLength(8)`.

## Testing

```scala
import dev.taihei.i18n.*
import zio.test.*

assertTrue(
  greeting.translate(Language.Dutch).contains("Alice"),
  Language.of("fr") == Language.French,
)
```

The suite runs on both platforms (`make test`); JS tests execute under Node.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Copyright
Innovenso BV. Versions up to and including 2.0.0 were published as
`fish.genius::genius-i18n` under the MIT License and remain available under it.
