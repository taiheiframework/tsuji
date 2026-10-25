# Taihei Tsuji

The tsuji (通詞) were the official interpreters of Nagasaki in the Edo period. Taihei Tsuji is a
type-safe internationalization library for Scala 3: languages, typed message families
checked when the code compiles, per-language catalogues, ICU MessageFormat 2.0, and
locale-aware number and date formatting. Cross-compiled for the **JVM and Scala.js**.

Part of the [Taihei Framework](https://taihei.dev), the open-source Scala 3
libraries from Innovenso. The package is `dev.taihei.tsuji`. Up to 4.x the library was called
taihei-i18n: see [Coming from taihei-i18n 4.x](#coming-from-taihei-i18n-4x).

## Modules

Published on Maven Central under `dev.taihei`:

| Artifact                | Platform | Purpose                                                       |
| ----------------------- | -------- | ------------------------------------------------------------- |
| `taihei-tsuji`          | JVM      | The library on `java.time` / `java.text`.                     |
| `taihei-tsuji_sjs1`     | Scala.js | The same API, formatting via the browser `Intl` API.          |
| `taihei-tsuji-tools`    | JVM      | A program that exports catalogue files for translators and browsers. |

```scala
mvn"dev.taihei::taihei-tsuji:5.0.0"          // Mill, JVM
mvn"dev.taihei::taihei-tsuji::5.0.0"         // Mill, Scala.js
mvn"dev.taihei::taihei-tsuji-tools:5.0.0"    // Mill, the export program
"dev.taihei" %%% "taihei-tsuji" % "5.0.0"      // sbt
"dev.taihei" %% "taihei-tsuji-tools" % "5.0.0" // sbt
```

The `shared/` sources compile on both platforms; `jvm/` and `js/` hold only the
platform-specific formatters and the loading of catalogues.

5.0.0 is the first release under the name Taihei Tsuji, and a major release: see
[Coming from taihei-i18n 4.x](#coming-from-taihei-i18n-4x). The 4.x line keeps its name,
`dev.taihei::taihei-i18n`, and lives on branch `4.0.x` of this repository: taihei-i18n 4.0.x
receives security fixes for 12 months after the release of Taihei Tsuji 5.0.0.

## Stack

- JVM: Java 17 or later. The JVM modules are compiled against the Java 17 API
  (`-java-output-version:17`), and CI runs the tests on Java 17 and on Java 25.
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
make validate       # repo-guard + release-test + fix + fmt + compile + test + mima
make repo-guard     # Maven Central only: no other repository, every dev.taihei pin on Central
make release-test   # the release machinery's own tests, against a fake registry on localhost
make mima           # binary compatibility with the newest release of the current major on Central
make publishLocal   # to ~/.ivy2/local (a SNAPSHOT only)
```

## Releasing

The version is the git tag: an exact `v`-tag on a clean tree is the release,
anything else a SNAPSHOT of the last tag.

```sh
make release-prepare TYPE=patch|minor|major EMBARGO=0|1
                                               # validate, write RELEASE_x_y_z.md
make release-finalize                          # commit the notes, tag locally, check
                                               # both registries, build and sign the
                                               # bundle once, upload it to GitHub
                                               # Packages and read every file back
git push origin main && git push origin vX.Y.Z # only after the read-back succeeded
make release-github                            # the bundle on an immutable GitHub Release
make publish-dry-run                           # the signed bundle, written to out/
```

One release publishes `taihei-tsuji` (JVM and Scala.js) and `taihei-tsuji-tools` with the same
version, as one signed bundle. Its files go unchanged to this repository's GitHub Packages
registry first, and to Maven Central with the next release train, so a new version can take up to
a month to reach Central; `SHA256SUMS` on the GitHub Release lists the SHA-256 of every file, and
both registries serve exactly those bytes. A security fix goes to Central first (`EMBARGO=1`).
The build itself resolves from Maven Central only, and `make validate` checks it (`repo-guard`).
A release publishes only the commit `release-prepare` validated, and refuses when a file git does
not track is in a source or resource folder, or a stray build file is in the tree.

`make publish` is only for a security fix under embargo, which goes to Central first; it refuses
any other version, and a version whose Central upload it started before. It reads the Central
Portal user token and the release key from `~/.edo-release.env` (a name from the Edo Framework
days, kept because the maintainers' workstations write it for every library; `RELEASE_ENV=…` on
the make command line points elsewhere, and the file must be yours, mode 600, not a link) or the
environment (`MILL_SONATYPE_USERNAME`, `MILL_SONATYPE_PASSWORD`, `MILL_PGP_SECRET_BASE64`,
`MILL_PGP_PASSPHRASE`). The dry run that builds the release bundle gets the release key but never
the Central token. The `mill` launcher downloads Mill over HTTPS only and keeps a download only
when its SHA-256 matches the one pinned in the launcher; a copy already in Mill's cache is not
checked again. The upload to GitHub Packages reads a `write:packages` token from
`~/.taihei-packages-publish.env` alone, and the read-back a `read:packages` token from Coursier's
credentials file (host `maven.pkg.github.com`); neither is ever in `~/.edo-release.env`.
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

A `Language` is a BCP 47 tag on every platform, and its JSON form is that tag. A platform's
locale type only appears as the argument of a factory, never in a return type or a field.

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

`language.locale` is deprecated since 5.1.0. In 6.0.0 it moves off the shared `Language`: on the
JVM `language.locale` keeps working as an extension method that needs no import, on Scala.js it
is gone. `java.util.Locale.forLanguageTag(language.tag)` works in 5.x and 6.x on both platforms.

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
  `{"format":1,"language":"de","messages":{"order.shipped":"...","order.cancelled":"..."}}`.

To show a message in language L, a `Catalogues` registry uses the first of these that has the key:

1. L exactly (`zh-Hant-TW`);
2. L without its extensions and private use (`-u-...`, `-x-...`), then with its last subtag
   removed, step by step (`zh-Hant`, then `zh`);
3. the fallbacks configured for L, each walked the same way:
   `Catalogues(families, fallbacks = Map(Language("lb") -> Seq(Language("de"))))`;
4. the family's source language, which always has it.

Numbers, dates and plurals are formatted for L, also when the template comes from a fallback. A
catalogue template wins over an inline one; in the source language the template in the code wins.
A template that fails its checks is skipped and named in `catalogues.problems`. Showing a message
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
`catalogues.onAdded` says when it did. The library has no dependency on Laminar or on an effect
library. A Laminar application turns the callback into a signal:

```scala
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.*

val catalogues       = Catalogues(Seq(Orders) ++ CoreMessages.families)
given Catalogues     = catalogues
val language         = Var(Language.English)
val loaded           = Var(0)
catalogues.onAdded(_ => loaded.update(_ + 1))

def switchTo(l: Language): Unit =
  language.set(l)
  CatalogueLoader.load(catalogues, l, s"/i18n/${l.tag}.json")

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
def runMvnDeps = Seq(mvn"dev.taihei::taihei-tsuji-tools:5.0.0")
// ./mill app.runMain dev.taihei.tsuji.tools.ExportCatalogues --out out/tsuji --scan com.acme --scan dev.taihei.tsuji
```

```scala
// sbt: run it forked, so that its classpath is the application's
libraryDependencies += "dev.taihei" %% "taihei-tsuji-tools" % "5.0.0"
Compile / run / fork := true
// sbt "runMain dev.taihei.tsuji.tools.ExportCatalogues --out target/tsuji --scan com.acme --scan dev.taihei.tsuji"
```

`--scan <package>` finds every family object in a package; `--family <object>` names one.
Templates that fail their checks are left out and printed, and make the exit status 1.

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
- `Message.encoder(withText = false)` leaves out `text`.
- Reading a message needs its definition: the decoder (`given` with a `Catalogues` in scope) looks
  up the key among the registry's families to read the arguments with their types. **Register
  every family whose messages you read** (`Catalogues(Seq(...))` or `register`): a key the
  registry does not know reads as its `text`, shown as it is in every language, and is an error
  without one.
- A `Text` is `{"text":"Disk full","language":"en"}`.
- The 4.x shape (`args`, `namedArgs`, `translationTemplates`) is still read, as a legacy message
  under the key `legacy`, so stored 4.x JSON and a 4.x peer keep working.
- Input is bounded: messages nest at most 8 deep, the kept arguments of a message with an unknown
  key at most 16 levels and 100000 values, a list holds at most 10000 elements, a 4.x
  message brings at most 1000 templates and 1000 arguments of at most 64K characters, a
  catalogue is at most 4M characters, 100000 messages and 16 levels of JSON nesting, and one
  template formats to at most a million characters (past that it falls back). A catalogue, or an
  object in the kept arguments of an unknown message, in which more than 16 keys share one hash
  code is refused: real keys almost never share one, and maps of keys that do cost time out of
  proportion to their size.
- On the JVM a registry looks a family's file for a language up on the classpath once and keeps
  it; of the languages without a file it remembers the latest 4096. Narrow a language from a
  request header to the languages you support before you show messages in it, or each new tag
  costs a lookup per family.
- One message, with its nested messages, list elements and fallbacks, produces at most four
  million characters; past that it shows its own template.

Versioning rules for keys, on the wire:

- A key is a contract. Never reuse a key for a different set of arguments.
- Adding an argument means a new key, unless the new argument is an `Option`: a reader that does
  not have it sees `None`.
- Removing or retyping an argument means a new key.
- The envelope above is format 1 of taihei-tsuji 5.x. A change to it would be a major release.
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
MessageFormat2.validate("Hello {$name")               // List(TemplateError(6, a placeholder is not closed with }))
```

Functions: `{$n :number}` (with `style=percent` or `style=currency`), `{$n :integer}`,
`{$d :datetime dateStyle=long timeStyle=short}`, `{$d :date}`, `{$t :time}`, `:plural` and
`:select` as `.match` selectors, `:string`. Declarations: `.input {$n :number}` and
`.local $x = {$n :number}` (or `.let`). `.match` variants are `when <keys> {pattern}`, and one of
them must be `when * {...}`. On the JVM a long or full `timeStyle` on a value without a time zone
formats as medium; Scala.js prints the runtime's zone instead. Formats come from the platform's
locale data (the JDK's CLDR version, or `Intl` in the JavaScript runtime), so a detail can differ
between them: a medium Dutch date is `20 okt. 2026` on Java 17 and `20 okt 2026` on later JDKs.

## Pre-built messages

`CoreMessages` holds families of common messages, English as the source language and Dutch and
French as catalogue files in the jar: `General`, `Authentication`, `Authorization`, `Validation`,
`Exceptions`, and `Languages` (what `Language.label` uses). `CoreMessages.families` lists them.

```scala
given Catalogues = Catalogues(CoreMessages.families)
CoreMessages.Validation.stringMinLength(min = 8).localize(Language.Dutch)  // lengte moet minimaal 8 zijn
CoreMessages.Validation.formatErrors(Map("email" -> List(CoreMessages.Validation.stringValidEmail)))
  .localize(Language.French)  // Échec de validation: email (Doit être une adresse e-mail valide)
```

In Scala.js the Dutch and French files reach the browser through the export, like your own.

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
4.x type stays, unchanged and deprecated, as `dev.taihei.tsuji.legacy.Message` until 6.0.0.
Moving to 5.0.0 starts with the new coordinates and a change of imports:

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
| `given JsonCodec[Message]`, needing nothing | `JsonEncoder[Message]` as before; `JsonDecoder` and `JsonCodec[Message]` need a `given Catalogues` where they are summoned or derived |
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
taihei-i18n 4.0.x first, then to Taihei Tsuji 5.0.0 as [above](#coming-from-taihei-i18n-4x).
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
