# Taihei Tsuji

The tsūji (通詞) were the official interpreters of Nagasaki in the Edo period. Taihei Tsuji is a
type-safe internationalization library for Scala 3: languages, typed message families
checked when the code compiles, per-language catalogues, ICU MessageFormat 2.0, and
locale-aware number and date formatting. Cross-compiled for the **JVM and Scala.js**.

Part of the [Taihei Framework](https://taihei.dev), the open-source Scala 3
libraries from Innovenso. The package is `dev.taihei.tsuji`. Up to 4.x the library was called
taihei-i18n: see [Coming from taihei-i18n 4.x](#coming-from-taihei-i18n-4x).

## Modules

Published under `dev.taihei`. Versions up to 5.0.2 are on Maven Central; 6.0.0 is not, it is
published to the Taihei Framework's own Maven repository.

| Artifact                     | Platform | Purpose                                                       |
| ---------------------------- | -------- | ------------------------------------------------------------- |
| `taihei-tsuji`               | JVM      | The library on `java.time` / `java.text`, with jsoniter-scala codecs. |
| `taihei-tsuji_sjs1`          | Scala.js | The same API, formatting via the browser `Intl` API.          |
| `taihei-tsuji-zio-json`      | JVM      | zio-json codecs for Tsuji's types, for applications that use zio-json. |
| `taihei-tsuji-zio-json_sjs1` | Scala.js | The same codecs on Scala.js.                                  |
| `taihei-tsuji-tools`         | JVM      | A program that exports catalogue files for translators and browsers. |

```scala
mvn"dev.taihei::taihei-tsuji:6.0.0"             // Mill, JVM
mvn"dev.taihei::taihei-tsuji::6.0.0"            // Mill, Scala.js
mvn"dev.taihei::taihei-tsuji-zio-json:6.0.0"    // Mill, zio-json codecs (JVM; :: for Scala.js)
mvn"dev.taihei::taihei-tsuji-tools:6.0.0"       // Mill, the export program
"dev.taihei" %%% "taihei-tsuji" % "6.0.0"          // sbt
"dev.taihei" %%% "taihei-tsuji-zio-json" % "6.0.0" // sbt, zio-json codecs
"dev.taihei" %% "taihei-tsuji-tools" % "6.0.0"     // sbt
```

5.0.2 is a security release: if you use 5.0.0 or 5.0.1, upgrade. See the advisory
[GHSA-pjj4-5gjj-j2p8](https://github.com/taiheiframework/tsuji/security/advisories/GHSA-pjj4-5gjj-j2p8)
and the [release notes](RELEASE_5_0_2.md).

The `shared/` sources compile on both platforms; `jvm/` and `js/` hold only the
platform-specific formatters and the loading of catalogues.

6.0.0 is a major release: Tsuji's JSON codecs are jsoniter-scala's now, the zio-json codecs move
to `taihei-tsuji-zio-json`, and ZIO leaves the core's dependencies. See
[Coming from Tsuji 5.x](#coming-from-tsuji-5x). The 5.0.x line lives on branch `5.0.x` of this
repository and receives security fixes for 12 months after the release of 6.0.0.

5.0.0 was the first release under the name Taihei Tsuji: see
[Coming from taihei-i18n 4.x](#coming-from-taihei-i18n-4x). The 4.x line keeps its name,
`dev.taihei::taihei-i18n`, and lives on branch `4.0.x`: taihei-i18n 4.0.x receives security fixes
for 12 months after the release of Taihei Tsuji 5.0.0.

## Stack

- JVM: Java 17 or later. The JVM modules are compiled against the Java 17 API
  (`-java-output-version:17`), and CI runs the tests on Java 17 and on Java 25.
- Mill 1.3.1
- Scala 3.9.0 (LTS) · Scala.js 1.22.0
- jsoniter-scala-core 2.x, the core's one runtime dependency (JSON), pinned to an exact version
- zio-json 1.1.1, only in `taihei-tsuji-zio-json`
- zio-test 2.1.27, in the tests only
- scala-java-time 2.8 (JS `java.time` support)

The build resolves from Coursier's defaults (the local Ivy repository, then Maven Central), then
from the family's Maven repository (`REPO_URL` in the Makefile, built from the immutable GitHub
Releases) for `dev.taihei` alone, and from nothing else; none of them needs credentials.

Coursier asks the local Ivy repository first. A `./mill … publishLocal` in a tagged checkout of a
family library puts a release version there (`make publishLocal` refuses that and writes only a
SNAPSHOT); when the build resolves that version, the copy is found before Maven Central and
`REPO_URL`, and repo-guard refuses it ("not in the Coursier cache"). Remove it from
`~/.ivy2/local/dev.taihei/` before `make validate`.

## Build

```sh
make compile        # ./mill __.compile           (JVM + JS)
make test           # ./mill __.test.testForked    (JVM + JS; JS runs on Node)
make fmt            # ./mill __.reformat
make fix            # ./mill __.fix                 (scalafix)
make lint           # ./mill __.checkFormat and __.fix --check, as CI checks them
make validate       # repo-guard + release-test + fix + fmt + lint + compile + test + mima + no-zio
make repo-guard     # Coursier's defaults (local Ivy, Central), then REPO_URL for dev.taihei
                    # alone, nothing else; every resolved dev.taihei file checked
make release-test   # the release machinery's own tests, against stand-ins on localhost
make mima           # binary compatibility with the newest release tag of the current major,
                    # which must be on Central or REPO_URL (MIMA_BASELINE=x.y.z until it is)
make publishLocal   # to ~/.ivy2/local (a SNAPSHOT only)
```

## Releasing

The version is the git tag: an exact `v`-tag on a clean tree is the release,
anything else a SNAPSHOT of the last tag.

Until the family's release workflow takes over (ADR 0009), a release takes the interim route
below, which only the maintainer runs, at his own terminal:

```sh
make release-prepare TYPE=patch|minor|major EMBARGO=0|1 TICKET=<key>
                                               # validate, write RELEASE_x_y_z.md
make release-finalize                          # repo-guard, commit the notes under the
                                               # release's issue key, tag locally, check
                                               # Central, REPO_URL and GitHub, build and
                                               # sign the bundle once; uploads nothing
git push origin main && git push origin vX.Y.Z # from here on the version is final
make release-github                            # publishes it: the bundle on an immutable
                                               # GitHub Release, read back and compared
make publish-repo                              # the repository's deploy, then every file
                                               # read back (verify-repo)
make release-abort                             # until the tag (and the notes commit)
                                               # is pushed: undo the preparation
make publish-dry-run                           # the signed bundle, written to out/
```

`release-prepare` refuses while the previous release has not finished: the family's Maven
repository must serve it, or, for a version from before ADR 0009, Maven Central.

A security fix (`EMBARGO=1`) is built and signed the same way. Its embargo is the tag that is
not pushed yet: `main` and the tag stay on the maintainer's machine until the time set for the
advisory, then go out in one sitting, followed by `make release-github`, `make publish-repo` and
the advisory. Its release notes say only that it is a security fix. Every release step refuses
while a `.release-embargo` file exists, the earlier machinery's list of versions under embargo.

One release publishes `taihei-tsuji` and `taihei-tsuji-zio-json` (each for the JVM and Scala.js) and
`taihei-tsuji-tools` with the same version, as one signed bundle, built and signed once. Its zip and
`SHA256SUMS`, the SHA-256 of every file, are kept on an immutable GitHub Release of the tag; that
release publishes the version. The family's Maven repository is built from those releases alone, so
nothing is ever uploaded to it, and it serves exactly the bytes `SHA256SUMS` lists. No new version
of this line goes to Maven Central (ADR 0009). A security fix for a line whose users are on
Central is released from that line's maintenance branch, which keeps the Central targets.

`make validate` checks the build's repositories (`repo-guard`): Coursier's defaults (the local
Ivy repository, then Maven Central), then `REPO_URL` for `dev.taihei` alone, and nothing else,
for every module that resolves dependencies, published or not; every `dev.taihei` pin on one of
them; every file the build resolved from one of them; and every resolved `dev.taihei` file in
the `SHA256SUMS` of its version's immutable GitHub Release and signed by the release key
(`keys/release-key.asc`), or, for a version without a release, on Central with a good signature
by that key.

repo-guard asks the GitHub API without a token: when the API answers HTTP 403 or 429 (its limit
for anonymous requests), wait an hour and run it again. When it names a file other than
`dev.taihei` in the Coursier cache of `REPO_URL`, remove that repository's directory from the
cache (under `~/.cache/coursier/v1/https/` on Linux, `~/Library/Caches/Coursier/v1/https/` on
macOS, the directory named after `REPO_URL`'s host and path) and build again.

A release publishes only the commit `release-prepare` validated, and refuses when a file git
does not track is in a source or resource folder, or a stray build file is in the tree.
Credentials never go on make's command line (`make … VAR=value`): make hands such a value on to
every command it runs, also after an `unset`. They come from `~/.edo-release.env` or the
environment. From the file only the dry run's signing step gets them, and only the release
key; do not export them in the shell you build in, where every Mill call of a build target
and of `release-prepare` would see them (repo-guard, `release-preflight` and the dry run unset
them first).

The dry run that builds the release bundle reads the release key from `~/.edo-release.env` (a name
from the Edo Framework days, kept because the maintainers' workstations write it for every
library; `RELEASE_ENV=…` on the make command line points elsewhere, and the file must be yours,
mode 600, not a link) or the environment (`MILL_PGP_SECRET_BASE64`, `MILL_PGP_PASSPHRASE`), and
never the Central token, neither from that file nor from the shell. The `mill` launcher downloads
Mill over HTTPS only, and runs Mill only when its SHA-256 matches the one pinned in the launcher:
it checks a download before keeping it, and the file it is about to run before every run, so a
tampered copy in Mill's cache is refused, as is a version without a pin. `release-preflight`
(inside `release-finalize`), `release-github` and `publish-repo` use the maintainer's `gh` login;
nothing else in a release needs a token.
Releases are signed by the Taihei Framework release key, fingerprint
`5026 B299 6DDB 697E C463  0BCF 14A7 33E2 A507 702F`, with the user ids
`Taihei Framework Releases <releases@taihei.dev>` and, from before the family was renamed,
`Edo Framework Releases <releases@edoframework.com>`. The fingerprint is what to verify. The
key is published on keyserver.ubuntu.com and keys.openpgp.org.

## Quick start

```scala
import java.time.LocalDate
import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

object Orders extends MessageFamily("order"):
  val cancelled = message("Your order was cancelled", Dutch -> "Uw bestelling werd geannuleerd")
  val shipped   = message[(count: Int, on: LocalDate)](
    ".match {$count :plural} when one {One item shipped on {$on :date dateStyle=long}} " +
      "when * {{$count} items shipped on {$on :date dateStyle=long}}",
    Dutch -> (".match {$count :plural} when one {Eén artikel verzonden op {$on :date dateStyle=long}} " +
      "when * {{$count} artikels verzonden op {$on :date dateStyle=long}}"),
  )

given Catalogues = Catalogues(Seq(Orders))

val shipped = Orders.shipped(count = 3, on = LocalDate.of(2026, 10, 23))
shipped.localize(English)          // 3 items shipped on October 23, 2026
shipped.localize(Dutch)            // 3 artikels verzonden op 23 oktober 2026
shipped.localize(Language("de"))   // 3 items shipped on 23. Oktober 2026
```

## From the platform locale

A `Language` is a BCP 47 tag on every platform, and its JSON form is that tag, or a built-in's name
(`"English"`); both forms are read. The class holds no
platform locale type: on the JVM `java.util.Locale` appears only in the companion, as the argument
of `fromLocale` and the result of `locale`.

On the JVM, `Language.fromLocale` reads a `java.util.Locale`:

```scala
import java.util.Locale
import dev.taihei.tsuji.Language

Language.fromLocale(Locale.forLanguageTag("nl-BE")) // Right(nl-BE)
Language.fromLocale(Locale.ROOT)                    // Left(...): names no language
```

It is `Language.parse(locale.toLanguageTag)`, except that a locale without a language (`und`) is
refused. The JDK maps its legacy codes first (`iw` gives `he`), and extensions stay as the JDK
writes them: `th_TH_TH` gives `th-TH-u-nu-thai-x-lvariant-th`, which still finds the `th` messages
and formats with Thai digits. `locale.stripExtensions` drops the `-u-` extensions but keeps such
a legacy variant as `-x-lvariant-`; `Language.fromLocale(locale).map(_.base)` gives the language
alone.

On both platforms, `Language.preferred` lists the languages the platform prefers, most preferred
first: on the JVM the default display locale, in JavaScript `navigator.languages`, else
`navigator.language`, else the locale of `Intl.DateTimeFormat`. Tags that name no language, are
not well formed or repeat are left out, so the list may be empty and you choose the fallback:

```scala
val language = Language.preferred.headOption.getOrElse(Language.defaultLanguage)
```

In JavaScript an `Intl.Locale` is read by its tag: `Language.parse(intlLocale.toString)`, or
`Language.parse(intlLocale.baseName)` without its extensions. `language.script` and
`language.region` give the script and region subtags (`zh-Hant-TW`: `Some("Hant")`,
`Some("TW")`).

On the JVM only, `language.locale` goes the other way: it is
`java.util.Locale.forLanguageTag(language.tag)`, and `Language.fromLocale(language.locale)` gives
the language back. It is an extension method in the companion, so it needs no import:

```scala
import dev.taihei.tsuji.Language

Language("nl-BE").locale // nl_BE, on the JVM only
```

On Scala.js `language.locale` does not exist: use `language.tag`, or
`new Intl.Locale(language.tag)` where JavaScript needs a locale object.

## From a request

On a server the reader's languages come from the request instead, usually its `Accept-Language`
header. Show messages in one of the languages your application supports, not in whatever tag a
reader sends: `Language.fromAcceptLanguage` reads the header, most preferred first, and
`Language.negotiate` picks the first supported language among them, or your default:

```scala
val supported = Seq(Language.Dutch, Language.French, Language.English)

def readerLanguage(acceptLanguage: String): Language =
  Language.negotiate(
    requested = Language.fromAcceptLanguage(acceptLanguage),
    supported = supported,
    default = Language.English,
  )

readerLanguage("nl-BE,nl;q=0.9,en;q=0.8") // Dutch
readerLanguage("fr-CA, *;q=0.1")          // French
readerLanguage("de, it;q=0.5")            // English
```

`fromAcceptLanguage` orders the entries by weight, leaves out those with `q=0` and those weighted
below a `*` (which stands for any language no other entry names, so for your default), and skips an entry it cannot read; it never throws
and reads at most 4096 characters and 32 entries of the header. `negotiate` walks each requested
language down as a registry does to find a message, from `zh-Hant-TW` to `zh-Hant` to `zh`, so
`nl-BE` chooses a supported `nl`. It never goes the other way: a request for `en` does not choose
a supported `en-GB`, so list the plain languages you support too. The `fallbacks` of a
`Catalogues` play no part: with `lb -> de` configured, list `lb` in `supported` to show it.
The same works for a language from a URL parameter or a profile: `Language.parse(value).toSeq`
gives the requested list.

## Messages

A **message family** is an object that groups messages under a key prefix and names its
**source language**, English unless it says otherwise (`MessageFamily("order", sourceLanguage =
Dutch)`). Each message is a `val` defined with `message`:

- `message("text", translations*)` is a `Message` without arguments;
- `message[(count: Int, on: LocalDate)]("template", translations*)` is a `MessageDefinition`.
  Applied to its arguments, by name or by position, it gives a `Message`:
  `Orders.shipped(count = 3, on = today)`. The compiler checks the names and the types.

The key of a message is the prefix, a dot and the val's name: `order.shipped`. A message is a
plain `val` of the family object; in a `def`, a nested object or a class it does not compile, and
a `lazy val` joins the family's definitions only when first used, so `verify`, the export and a
registry that reads its messages from JSON do not see it before. A `Message` is
that key and the argument values, nothing else; its translations stay in the family and in
catalogues.

**Checked when the code compiles**, by a macro inside the library (no build plugin, any build
tool), for the template and every inline translation written as a literal: the template is valid
MF2; every variable is an argument or a `.local` declared before it; `:number`, `:integer` and
`:plural` apply to numbers, `:datetime` to dates and times, `:date` and `:time` to their part; a
`.match` has a `*` variant; argument names are valid. An argument a template does not use is a
warning, because some languages leave out what others say.

**Arguments** are typed. `MessageArg[A]` exists for `String`, `Int`, `Long`, `Double`, `Float`,
`Boolean`, `LocalDate`, `LocalDateTime`, `LocalTime`, `Language` (shown as its name in the
reader's language), a nested `Message`, `Text` or `Displayable` (shown in the reader's language),
and `Option` and `List` of these. A type of your own goes through one of them:

```scala
final case class OrderId(value: String)
given MessageArg[OrderId] = MessageArg.as[OrderId, String](_.value, s => Right(OrderId(s)))
```

The functions of your own types are checked by `Catalogues.verify` in your tests, not by the
compiler.

**Text** is text in one language that is not a message of a family: an exception's message,
or a text a server already translated. `Text.of(throwable)`, `Text("Disk full", English)`. It shows
as it is, whatever the reader's language. `Message`, `Text` and the 4.x `legacy.Message` share the
supertype `Displayable`, shown with `localize(language)` and a `Catalogues` in scope.

## Translations and the fallback chain

Translations come from two places, both checked the same way:

- **Inline**, as trailing `Language -> template` pairs of `message`. They suit a JVM application,
  a library's own few languages, and tests. Inline translations are code: **in Scala.js they are
  part of the bundle.** A browser application with many languages keeps them in files.
- **Catalogue files**, one per language, written by translators or exported:
  `{"format":1,"language":"de","messages":{"order.shipped":"...","order.cancelled":"..."}}`,
  in UTF-8 without a byte order mark: a file that starts with one is refused, on the JVM and in
  the browser, with a problem that says so.

To show a message in language L, a `Catalogues` registry uses the first of these that has the key:

1. L exactly (`zh-Hant-TW`);
2. L without its extensions and private use (`-u-...`, `-x-...`), then with its last subtag
   removed, step by step (`zh-Hant`, then `zh`);
3. the fallbacks configured for L, each walked the same way:
   `Catalogues(families, fallbacks = Map(Language("lb") -> Seq(Language("de"))))`;
4. the family's source language, which always has it.

Numbers, dates and plurals are formatted for L, also when the template comes from a fallback. A
catalogue template wins over an inline one; in the source language the template in the code wins.
A template that fails its checks, or cannot be formatted (its text would pass a million
characters, the message with its nested messages four million, or formatting threw), is skipped
and named in `catalogues.problems` with its language and the kind of cause. Showing a message
never throws.

### On the JVM

A library or application ships its catalogues as resources:
`META-INF/taihei-tsuji/<family prefix>/<language tag>.json`, for example
`META-INF/taihei-tsuji/order/de.json`. The registry reads a family's file for a language the first
time that language is needed, and keeps it. Nothing needs to be configured.

### In Scala.js

The bundle holds the definitions and the source-language templates: one language, not all of
them. The application serves the per-language files that the export writes, and loads a language
with `CatalogueLoader.load(catalogues, language, url)`, which returns a `Future` with what went
wrong, if anything. Until a language arrives, its messages show through the fallback chain;
`catalogues.onAdded` says when it did. `CatalogueLoader` needs a `TextDecoder` that honours
`fatal` and `ignoreBOM`, as every current browser and Node do; with one that ignores them (an old
polyfill) every load is a problem. A server that answers slowly or not at all keeps a load
waiting; since 6.1.0, `CatalogueLoader.load(catalogues, language, url, timeout = Some(10.seconds))`
gives up after that time with a problem, aborts the request and lets its body go. Without a
timeout it waits as long as the server and the runtime let it. The library has no dependency on
Laminar or on an effect library. A Laminar application turns the callback into a signal:

```scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import com.raquo.laminar.api.L.*

val catalogues       = Catalogues(Seq(Orders) ++ CoreMessages.families)
given Catalogues     = catalogues
val language         = Var(Language.English)
val loaded           = Var(0)
catalogues.onAdded(_ => loaded.update(_ + 1))

def switchTo(l: Language): Unit =
  language.set(l)
  CatalogueLoader.load(catalogues, l, s"/i18n/${l.tag}.json", timeout = Some(10.seconds))

div(child.text <-- language.signal.combineWith(loaded.signal).map((l, _) => Orders.cancelled.localize(l)))
```

On a language switch the application fetches the next file; it may prefetch one, the library
does not.

### Exporting catalogues

`taihei-tsuji-tools` has one program, `dev.taihei.tsuji.tools.ExportCatalogues`. It finds the
families on the application's classpath, those of libraries such as `CoreMessages` included, and
writes one merged file per language: `<out>/<tag>.json`. The file of the source language is the
one to hand to translators; the others hold the catalogue files on the classpath over the inline
translations. It runs on the JVM, so it runs in a JVM module whose classpath holds the families:
for a cross-built application, the JVM side of its shared code (a family defined only in Scala.js
code cannot be exported). It is an ordinary run step, not a build plugin:

```scala
// Mill: a module with the tools on its run classpath
def runMvnDeps = Seq(mvn"dev.taihei::taihei-tsuji-tools:6.0.0")
// ./mill app.runMain dev.taihei.tsuji.tools.ExportCatalogues --out out/tsuji --scan com.acme --scan dev.taihei.tsuji
```

```scala
// sbt: run it forked, so that its classpath is the application's
libraryDependencies += "dev.taihei" %% "taihei-tsuji-tools" % "6.0.0"
Compile / run / fork := true
// sbt "runMain dev.taihei.tsuji.tools.ExportCatalogues --out target/tsuji --scan com.acme --scan dev.taihei.tsuji"
```

`--scan <package>` finds every family object in a package; `--family <object>` names one.
Templates that fail their checks are left out and printed, and make the exit status 1; so does
a family object that cannot be loaded, such as one whose initialiser throws.

### Checking catalogues in your tests

```scala
// Any platform: your families and the catalogues you have
assertTrue(Catalogues.verify(Seq(Orders), Seq(dutchFile), required = Set(Language.Dutch)).isEmpty)

// JVM: the files on the classpath
assertTrue(ClasspathCatalogues.verify(Seq(Orders), Seq(Dutch, French), required = Set(Dutch)).isEmpty)
```

`verify` checks every template in the code and in the catalogues against the definition of its
key, that no catalogue has a key without a definition, that every **required** language has every
key (inline or in a file; other languages may be partial and fall back), and that keys are unique
across families. Completeness of files is a test-time check: checking files when the code compiles
would make the build read files a compiler cannot track.

## On the wire

A `Message` is written as its key and typed arguments, and by default its text in the source
language:

```json
{"key":"order.shipped","args":{"count":3,"on":"2026-10-23"},"text":"3 items shipped on October 23, 2026"}
```

- A number is a number, a date an ISO 8601 string, a language its tag.
- Reading a message needs its definition: the decoder looks up the key among the families of the
  `Catalogues` in scope to read the arguments with their types. **Register every family whose
  messages you read** (`Catalogues(Seq(...))` or `register`): a key the registry does not know
  reads as its `text`, shown as it is in every language, and is an error without one.
- A `Text` is `{"text":"Disk full","language":"en"}`.
- The 4.x shape (`args`, `namedArgs`, `translationTemplates`) is still read, as a legacy message
  under the key `legacy`, so stored 4.x JSON and a 4.x peer keep working.

### Codecs: jsoniter-scala

Tsuji's codecs are [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala)'s
`JsonValueCodec` and `JsonKeyCodec`, as givens in the companions of the types, so no import is
needed beyond jsoniter-scala's own `core.*`:

| Type | Givens |
| --- | --- |
| `Language` | `JsonValueCodec[Language]`, `JsonKeyCodec[Language]` |
| `MessageKey` | `JsonValueCodec[MessageKey]`, `JsonKeyCodec[MessageKey]` |
| `Text` | `JsonValueCodec[Text]` |
| `Message` | `JsonValueCodec[Message]` with a `Catalogues` in scope; `Message.codec(withText)` |
| `Displayable` | `JsonValueCodec[Displayable]` with a `Catalogues` in scope |
| `legacy.Message` | `JsonValueCodec[legacy.Message]` |

```scala
import com.github.plokhotnyuk.jsoniter_scala.core.*
import dev.taihei.tsuji.*

given Catalogues = Catalogues(Seq(Orders))

val json    = writeToString(Orders.cancelled) // {"key":"order.cancelled","text":"Your order was cancelled"}
val message = readFromString[Message](json)    // the same message
writeToString(Orders.cancelled)(using Message.codec(withText = false)) // without "text"
```

- A codec is one type for both directions, so writing a `Message` or a `Displayable` also asks for
  a `Catalogues`, although writing does not use it. A program that only writes passes
  `Catalogues()`.
- A decoding error is a `JsonReaderException`, as with every jsoniter-scala codec; the codecs
  throw nothing else on any input. `Catalogue.parse` returns a `Left` instead.
- Tsuji's values are always written compactly, inside your own codecs too: the indentation and
  `escapeUnicode` of your `WriterConfig` do not reach inside them. A string with an unpaired
  surrogate is written with U+FFFD, the replacement character, in its place, so writing never
  throws.
- Reading follows the JSON grammar of RFC 8259 strictly. Your `ReaderConfig` decides what
  jsoniter-scala's entry points decide: whether content after the value is refused
  (`checkForEndOfInput`, on by default) and how long a string may be (`maxCharBufSize`, 4,194,304
  characters by default). `readFromString` encodes the `String` as UTF-8 before it reads it, so an
  unpaired surrogate character in the `String` itself (not an escape) reads as `?`; input read
  from bytes cannot hold such a character.

### Codecs: zio-json

`taihei-tsuji-zio-json` holds zio-json codecs for the same types in one object, `TsujiZioJson`.
They write the same bytes as the jsoniter-scala codecs and read through them, with the same
bounds:

| Type | Givens in `TsujiZioJson` |
| --- | --- |
| `Language` | `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonFieldEncoder`, `JsonFieldDecoder` |
| `MessageKey` | `JsonEncoder`, `JsonDecoder`, `JsonFieldEncoder`, `JsonFieldDecoder` |
| `Text` | `JsonEncoder`, `JsonDecoder`, `JsonCodec` |
| `Message` | `JsonEncoder`; `JsonDecoder` and `JsonCodec` with a `Catalogues` in scope; `messageEncoder(withText)` |
| `Displayable` | `JsonEncoder`; `JsonDecoder` with a `Catalogues` in scope |
| `legacy.Message` | `JsonCodec` |

```scala
import zio.json.*
import dev.taihei.tsuji.*
import dev.taihei.tsuji.ziojson.TsujiZioJson.given

given Catalogues = Catalogues(Seq(Orders))

Orders.cancelled.toJson // the same JSON as above
json.fromJson[Message]  // Right(the message)
Orders.cancelled.toJson(using TsujiZioJson.messageEncoder(withText = false))
```

- A codec object of your own makes them its own with `export TsujiZioJson.given`, so that derived
  codecs of your types find them.
- Decoding a `Message` or a `Displayable` needs a `Catalogues` in scope; encoding needs none.
- A value that is already a zio-json AST (`.as[Message]`) is checked against the same bounds
  before it is decoded, and refused when it stands for more than 4194304 values and characters,
  a part placed in several places of the AST counted in each; a `null` in it is refused too.
  The bound holds for each Tsuji value: a collection of N of them in a built AST costs up to N
  times it, so bound what you build. Reading a member costs what zio's own `foreach` pays for it,
  a few microseconds at the worst for a `Chunk` built by many layers of `updated`.
- What follows the value is zio-json's to decide: `fromJson` ignores it, where the core's codecs
  under jsoniter-scala's default `ReaderConfig` refuse it.
- A value is read whole, members it does not use included, and held at a few times its length
  while it is decoded; no string in it has a length bound of its own, because no zio-json entry
  point has a length setting. Bound the input you read: a limit on a request's body, or `take` on
  a stream.
- zio-json's readers of bytes and streams replace malformed UTF-8 with U+FFFD before any decoder
  runs, so the module reads such input with U+FFFD in place, where the core's codecs reading the
  same bytes refuse it. To refuse it with the module, decode the bytes to a `String` with a
  decoder that reports errors first, or read the bytes with the core's codecs.

### Bounds

JSON of messages and texts is input your application did not compile, so reading it is bounded
in Tsuji's own codecs, whatever `ReaderConfig` you read with:

- The JSON of a message or a text is read at most 64 levels of arrays and
  objects deep (a catalogue 16), without recursion (a nested message takes two levels, a list
  around it one more, so the 8 levels of messages below fit with room to spare), and a number in
  a message, a text or a catalogue is at most 1100 characters long with an exponent of at most 9
  digits (leading zeros not counted) and a mantissa below 256 bits (about 77 digits);
  messages nest at most 8 deep, the kept arguments of a message with an unknown key at most 16
  levels and 100000 values, a list holds at most 10000 elements, a 4.x
  message brings at most 1000 templates and 1000 arguments of at most 64K characters, a
  catalogue is at most 4M characters, 100000 messages and 16 levels of JSON nesting, and one
  template formats to at most a million characters (past that it falls back). A catalogue, or an
  object in the kept arguments of an unknown message, in which more than 16 keys share one hash
  code is refused: real keys almost never share one, and maps of keys that do cost time out of
  proportion to their size.
- Every member is read and checked, also one the decoder does not use, and then dropped.
- An escape of an unpaired surrogate (`"\ud800"`) is a decoding error, and so is a number with a
  leading zero (`01`) or a decimal point without a digit on one side (`1.`, `-.5`).
- On the JVM a registry looks a family's file for a language up on the classpath once and keeps
  it; of the languages without a file it remembers the latest 4096. Narrow a language from a
  request header to the languages you support before you show messages in it
  (`Language.negotiate`, see From a request), or each new tag costs a lookup per family.
- One message, with its nested messages, list elements and fallbacks, produces at most four
  million characters; past that it shows its own template.

Versioning rules for keys, on the wire:

- A key is a contract. Never reuse a key for a different set of arguments.
- Adding an argument means a new key, unless the new argument is an `Option`: a reader that does
  not have it sees `None`.
- Removing or retyping an argument means a new key.
- The envelope above is format 1, the same in taihei-tsuji 5.x and 6.x. A change to it would be a
  major release.
  There is no version field per message.

And in Scala, for a family that a library publishes: a definition's arguments are its API. Code
compiled against an older version calls it with the arguments it knew, which the compiler of the
library cannot check, so it fails or goes wrong when it runs. Adding an argument (even an
`Option`), removing, renaming or retyping one is a breaking change of the library. Within a major
version, add a new val, with a new key, instead.

## ICU MessageFormat 2.0

Templates use the MF2 dialect this library has always read, and `MessageFormat2` formats one
directly:

```scala
val tmpl = ".match {$count :plural} when one {You have one item} when * {You have {$count} items}"

MessageFormat2.format(tmpl, Map("count" -> 1), "en")  // You have one item
MessageFormat2.format(tmpl, Map("count" -> 5), "en")  // You have 5 items
MessageFormat2.validate("Hello {$name")               // List(at 6: a placeholder is not closed with })
```

Functions: `{$n :number}` (with `style=percent` or `style=currency`), `{$n :integer}`,
`{$d :datetime dateStyle=long timeStyle=short}`, `{$d :date}`, `{$t :time}`, `:plural` and
`:select` as `.match` selectors, `:string`. Declarations: `.input {$n :number}` and
`.local $x = {$n :number}` (or `.let`). `.match` variants are `when <keys> {pattern}`, and one of
them must be `when * {...}`. On the JVM a long or full `timeStyle` on a value without a time zone
formats as medium; Scala.js prints `UTC` instead. On Scala.js a date outside the JavaScript Date
range (after year 275760 or before year -271821) shows as its ISO text, such as `+300000-01-01`.
Formats come from the platform's locale data (the JDK's CLDR version, or `Intl` in the JavaScript
runtime), so a detail can differ between them: a medium Dutch date is `20 okt. 2026` on Java 17
and `20 okt 2026` on later JDKs.

## Pre-built messages

`CoreMessages` holds families of common messages, English as the source language and Dutch and
French as catalogue files in the jar: `General`, `Authentication`, `Authorization`, `Validation`,
`Exceptions`, and `Languages` (what `Language.label` uses). `CoreMessages.families` lists them.
Every message has a Dutch and a French translation; the French ones put a no-break space before
`:` and a narrow one before `;`, `?` and `!`, as French typography wants.

```scala
given Catalogues = Catalogues(CoreMessages.families)
CoreMessages.Validation.stringMinLength(min = 8).localize(Language.Dutch)  // lengte moet minimaal 8 zijn
CoreMessages.Validation.formatErrors(Map("email" -> List(CoreMessages.Validation.stringValidEmail)))
  .localize(Language.French)  // Échec de la validation\u00a0: email (doit être une adresse e-mail valide)
```

In Scala.js the Dutch and French files reach the browser through the export, like your own.

## Coming from Tsuji 5.x

6.0.0 changes three things: Tsuji's JSON codecs are jsoniter-scala's, with the zio-json codecs in
a module of their own; ZIO leaves the dependencies of the core; and `Language.locale` leaves the
shared class. The JSON itself does not change, apart from the cases listed under
[JSON on the wire](#json-on-the-wire). `legacy.Message` stays, deprecated, until 7.0.0. Tsuji 5.0.x
receives security fixes for 12 months after the release of 6.0.0.

### Dependencies

`taihei-tsuji` depends on jsoniter-scala-core and, on Scala.js, scala-java-time (with its tzdb),
and on nothing
else at run time: zio-json, zio, zio-streams and the libraries they bring are no longer on your
classpath through Tsuji. If your code uses ZIO, `zio.Chunk` or zio-json without depending on them
itself, add the dependency.

### If you use zio-json

1. Add `dev.taihei::taihei-tsuji-zio-json:6.0.0` next to `taihei-tsuji`.
2. Add `import dev.taihei.tsuji.ziojson.TsujiZioJson.given` where a zio-json codec of a Tsuji type
   is summoned or derived, or `export TsujiZioJson.given` in a codec object of your own.
3. `Message.encoder(withText = false)` becomes `TsujiZioJson.messageEncoder(withText = false)`.

The module writes the bytes 5.x wrote and reads what 5.x read, with the differences below. Its
givens have the same types as the 5.x givens, but they live in `TsujiZioJson`, not in the
companions.

### If you use jsoniter-scala, or start with JSON now

The companions hold `JsonValueCodec` and `JsonKeyCodec` givens; no import is needed. Decoding a
`Message` or a `Displayable` needs a `Catalogues` in scope, as before, and so does encoding one:
a program that only writes passes `Catalogues()`. See [Codecs: jsoniter-scala](#codecs-jsoniter-scala).

### What was removed or changed in the API

- **Removed from the companions**, the zio-json givens:
  - `Language`: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonFieldEncoder`, `JsonFieldDecoder`;
  - `MessageKey`: `JsonEncoder`, `JsonDecoder`, `JsonFieldEncoder`, `JsonFieldDecoder`;
  - `Text`: `JsonEncoder`, `JsonDecoder`, `JsonCodec`;
  - `Displayable`: `JsonEncoder`, `JsonDecoder`;
  - `Message`: `Message.encoder(withText)`, `JsonEncoder`, `JsonDecoder`, `JsonCodec`;
  - `legacy.Message`: `JsonCodec`, and the codecs of `legacy.MessageArg`, which nothing outside
    Tsuji needs.
- **Added**: the `JsonValueCodec` and `JsonKeyCodec` givens listed under
  [Codecs: jsoniter-scala](#codecs-jsoniter-scala), and
  `Message.codec(withText)`, which takes a `Catalogues`.
- **Given names change** with the types, for code that names a given:
  `Language.given_JsonCodec_Language` becomes `Language.given_JsonValueCodec_Language`.
- **Decoding errors** are jsoniter-scala's `JsonReaderException`, with other texts than
  zio-json's. Through the module they stay zio-json's `Left`.
- **Writing**: Tsuji's values are written compactly, also inside a `WriterConfig` that indents.
- **Binary**: a library compiled against Tsuji 5.x that uses any of the removed members fails
  against 6.0.0 until it is compiled again (a `NoSuchMethodError` on the JVM, a linking error on
  Scala.js).

### JSON on the wire

The JSON on the wire and in catalogue files does not change, and data written by 4.x or 5.x reads
as before. The exceptions are unpaired surrogates, a `Long.MinValue` number written on the JVM,
and input that is not strict JSON (a leading zero in a number, and content after the value in the
core's codecs). The release notes of 6.0.0 list each one.

### `Language.locale`

6.0.0 takes the JDK's locale type out of the shared `Language`, so that the class is the same on
every platform. `language.locale` moves from the class to an extension method in the JVM
companion. What that means for a caller:

- **Scala on the JVM:** nothing, except the note on `import Language.*` below. `language.locale`
  compiles unchanged, with no new import.
- **Scala.js:** `language.locale` no longer compiles. Use `language.tag`, or
  `java.util.Locale.forLanguageTag(language.tag)` where the code really needs a
  `java.util.Locale` (scala-java-locales provides it on Scala.js).
- **Java:** `language.locale()` becomes `Language.locale(language)`, a static method.
- **Binary, JVM and Scala.js:** the instance method `Language.locale()` is gone, so a library
  compiled against 5.x that calls it fails, with a `NoSuchMethodError` on the JVM and a linking
  error on Scala.js, until it is compiled again against 6.0.0.

`import Language.*`, the usual import for `English`, `Dutch` and `French`, now also brings the names
`preferred` and, on the JVM, `locale` and `fromLocale` into scope. Code in that scope that refers
to one of these names of its own without qualifying it (an inherited member or an outer definition)
can get an ambiguity error. Qualify that reference, `this.locale` for an inherited member and the
owner's name for an outer one, or import only the names you use (`import Language.{English, Dutch}`).
`language.script` and `language.region` are new members of `Language`: an extension method of your
own with either name is no longer called on a `Language`, because a member wins over an extension.
Rename it, or use the new member.

## Coming from taihei-i18n 4.x

Taihei Tsuji 5.0.0 is the successor of `dev.taihei::taihei-i18n` 4.x under a new name. The
coordinates and the package change with it:

- the dependency is `dev.taihei::taihei-tsuji` (and `dev.taihei::taihei-tsuji-tools`);
- the package is `dev.taihei.tsuji`, where it was `dev.taihei.i18n`;
- a library ships its catalogue files under `META-INF/taihei-tsuji/`.

The JSON of a message does not change, and neither do the keys of `CoreMessages`: a taihei-i18n
4.x peer and a Taihei Tsuji 5 peer exchange messages as described [below](#on-the-wire).

There is no taihei-i18n 5.0.0 and no relocation from the old coordinates. The two lines are
different artifacts in different packages, so they can be on one classpath side by side: a
library built against taihei-i18n 4.x, such as taihei-lorem 4.x, keeps working beside Taihei
Tsuji 5 with its own taihei-i18n jar. Its `dev.taihei.i18n.Language` and the
`dev.taihei.tsuji.Language` of 5.x are different types.

5.0.0 also replaces the 4.x `Message`, which carried every translation, with typed families. The
4.x type stays, unchanged and deprecated, as `dev.taihei.tsuji.legacy.Message` until 7.0.0.
Moving to Taihei Tsuji starts with the new coordinates and a change of imports. Take 6.0.0 and
follow [Coming from Tsuji 5.x](#coming-from-tsuji-5x) as well; if you stop on the 5.x line, take
5.0.2 or later, never 5.0.0 or 5.0.1 (advisory
[GHSA-pjj4-5gjj-j2p8](https://github.com/taiheiframework/tsuji/security/advisories/GHSA-pjj4-5gjj-j2p8)):

```sh
# every import of dev.taihei.i18n becomes dev.taihei.tsuji
grep -rlZ 'dev\.taihei\.i18n' --include='*.scala' . | xargs -0 perl -pi -e 's/dev\.taihei\.i18n/dev.taihei.tsuji/g'
```

```scala
import dev.taihei.tsuji.*
import dev.taihei.tsuji.legacy.Message   // an explicit import wins over the wildcard
```

Code that only builds and translates 4.x messages then compiles with deprecation warnings, and
legacy messages and new ones are shown side by side (both are `Displayable`). Code that uses
`Message.of`, `Language.label`, `CoreMessages`, the `MessageArg` union or the MF2 internals needs
the changes in the table below. To silence the warnings while you migrate, add
`-Wconf:cat=deprecation&origin=dev\.taihei\.tsuji\.legacy\..*:s` to the compiler options.

Then move the messages to families, one family at a time:

| 4.x | 5.x |
| --- | --- |
| `Message.with0Args(English -> "Saved", Dutch -> "Opgeslagen")` | `val saved = message("Saved", Dutch -> "Opgeslagen")` in a `MessageFamily` |
| `Message.withNamedArgs(English -> "Hello {$name}")("name" -> n)` | `val hello = message[(name: String)]("Hello {$name}")`, then `hello(name = n)` |
| `Message.with1Arg` … `with10Args` and `{$0}` | named arguments: `message[(count: Int, query: String)]("{$count} results for {$query}")` |
| `message.translate(language)`, `message.render(language)` | `message.localize(language)`, with a `given Catalogues` |
| `message.toString` (English) | `message.toString` (the family's source language) |
| `Message.of(throwable)` | `Text.of(throwable)` |
| `Message.of(string)` | `Text(string, Language.English)` |
| the union type `MessageArg` | the type class `MessageArg[A]`; the union is `legacy.MessageArg` |
| `MessageFormat2.Parser`, its AST, `Context` | internal now: use `MessageFormat2.format` and `MessageFormat2.validate` |
| `PlatformNumberFormatter`, `PlatformDateTimeFormatter`, `PlatformLocales`, `PlatformLanguageNames` | internal now: format through a template |
| `Language.label: legacy Message` | `Language.label: Message`, shown in any reader's language |
| `CoreMessages.X.member(args)` | the same names, with named, typed arguments |
| CoreMessages in Dutch and French on Scala.js, inline in 4.x | catalogue files now: export them (`--scan dev.taihei.tsuji`) and load them with `CatalogueLoader`, or a browser shows English |
| the case class `Message(args, namedArgs, translationTemplates)`, `copy`, its fields | only on `legacy.Message` |
| `message.nonEmpty` | `message.localize(language).nonEmpty` |
| `given JsonCodec[Message]`, needing nothing | `JsonEncoder[Message]` as before; `JsonDecoder` and `JsonCodec[Message]` need a `given Catalogues` where they are summoned or derived (in 6.0.0 these are in `taihei-tsuji-zio-json`: see [Coming from Tsuji 5.x](#coming-from-tsuji-5x)) |
| `CoreMessages.Exceptions.revisionMismatch(expected, actual)` | `CoreMessages.Exceptions.revisionMismatchBetween(expected, actual)` |
| `CoreMessages.Validation.formatErrors(Map[String, List[Message]])` | `formatErrors(Map[String, List[Displayable]])`; each field error is shown in the reader's language |
| `!!unknown message!!` | gone: the source language always has the key |

The step from string-named arguments to typed ones is where the compiler starts to help: a
template that names an argument it does not have, or formats a date as a number, no longer
compiles. Do it family by family.

JSON: stored or exchanged 4.x messages stay readable as legacy messages. A message a 5.x writer
produces is not readable by a 4.x reader: during a rollout, upgrade the readers (the clients)
first, or keep writing legacy messages until they have.

`Language` has the API of 4.x apart from `label`, which now returns the new `Message`.

In Scala.js, CoreMessages' Dutch and French were inline in 4.x and are files in 5.x: a browser
application shows them in English until it exports them and loads them like its own catalogues.

## Coming from edo-i18n 3.0.0 or genius-i18n

taihei-i18n 4.x has the API of `com.edoframework::edo-i18n:3.0.0` under its new name, with the
package `dev.taihei.i18n` where it was `com.edoframework.i18n`; coming from
`fish.genius::genius-i18n:2.0.0`, the package was `genius.i18n`. Move to the newest
taihei-i18n 4.0.x first, then to Taihei Tsuji 5 as [above](#coming-from-taihei-i18n-4x), and on to
6.0.0 as [Coming from Tsuji 5.x](#coming-from-tsuji-5x) says.
3.0.0 also moved to Scala 3.9 (LTS), so your build needs Scala 3.9 or later, to `zio-json` 1.1
instead of 0.9, and to the Apache License 2.0.

Do not stay on 2.0.0, 3.0.0 or 4.0.0. Parsing a long language tag can overflow the stack in
them; taihei-i18n 4.0.1 fixes it (advisory
[GHSA-45w8-x6c8-w4hg](https://github.com/taiheiframework/tsuji/security/advisories/GHSA-45w8-x6c8-w4hg)).
There is no patch under the older coordinates. Do not stay on taihei-i18n 4.0.1 either: decoding
or formatting a message read from untrusted JSON can use unbounded memory and CPU in it and every
earlier release; 4.0.2 fixes it (advisory
[GHSA-6h29-v54g-8337](https://github.com/taiheiframework/tsuji/security/advisories/GHSA-6h29-v54g-8337)).

## Testing

```scala
import dev.taihei.tsuji.*
import zio.test.*

test("messages") {
  given Catalogues = Catalogues(Seq(Orders))
  assertTrue(
    Orders.cancelled.localize(Language.Dutch) == "Uw bestelling werd geannuleerd",
    Catalogues.verify(Seq(Orders), Nil, required = Set(Language.Dutch)).isEmpty,
  )
}
```

The suite runs on both platforms (`make test`); JS tests execute under Node.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Copyright
Innovenso BV. Versions up to and including 2.0.0 were published as
`fish.genius::genius-i18n` under the MIT License and remain available under it. The library was
published as `fish.genius::genius-i18n` (up to 2.0.0), `com.edoframework::edo-i18n` (3.0.0),
`dev.taihei::taihei-i18n` (4.x) and, from 5.0.0, `dev.taihei::taihei-tsuji`.
