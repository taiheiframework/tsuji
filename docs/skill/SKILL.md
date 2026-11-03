---
name: taihei-tsuji
description: How to use Taihei Tsuji 6.x (dev.taihei::taihei-tsuji, formerly taihei-i18n, edo-i18n and genius-i18n), the type-safe internationalization library for Scala 3 on the JVM and Scala.js. Apply when writing or reviewing Scala that uses dev.taihei.tsuji.* - message families (MessageFamily, message[...]), Message, Text, Displayable, MessageArg, Catalogue and Catalogues, the fallback chain, catalogue files on the classpath or fetched with CatalogueLoader, Catalogues.verify in tests, the taihei-tsuji-tools export, the JSON wire format and its jsoniter-scala codecs, the zio-json codecs of taihei-tsuji-zio-json (TsujiZioJson), ICU MessageFormat 2.0 templates, CoreMessages, Language, or migrating from Tsuji 5.x or from taihei-i18n 4.x (dev.taihei.i18n) and its Message (now dev.taihei.tsuji.legacy.Message).
---

# Taihei Tsuji 6.x

A type-safe i18n library for Scala 3, cross-compiled for the JVM and Scala.js. Messages are
defined in Scala as typed **message families**, checked by a macro when the code compiles; their
translations live inline or in per-language **catalogue files**; a `Message` is only a key and
typed arguments, so only the reader's language travels and is loaded.

Package `dev.taihei.tsuji`. Artifacts: `dev.taihei::taihei-tsuji` (`taihei-tsuji_3` on the JVM,
`taihei-tsuji_sjs1_3` on Scala.js), `dev.taihei::taihei-tsuji-zio-json` (zio-json codecs, package
`dev.taihei.tsuji.ziojson`, JVM and Scala.js) and the JVM-only `dev.taihei::taihei-tsuji-tools`,
version 6.0.0 (not on Maven Central, which has the versions up to 5.0.2), Scala 3.9 (LTS), Apache
2.0. Source: <https://github.com/taiheiframework/tsuji>. The core's one runtime dependency is
jsoniter-scala-core; ZIO and zio-json come only with `taihei-tsuji-zio-json`. Tsuji 5.0.x gets
security fixes for 12 months after 6.0.0. Up to 4.x the library was `dev.taihei::taihei-i18n`,
package `dev.taihei.i18n`; that line stays under its old name on branch `4.0.x` and gets security
fixes for 12 months after Taihei Tsuji 5.0.0. Use the newest taihei-i18n 4.0.x on the 4.x line.

## When to use this skill

Whenever Scala code imports `dev.taihei.tsuji.*`: defining or showing messages, adding
translations, loading catalogues in a browser, sending messages over JSON, testing catalogues,
formatting numbers and dates per locale, or moving code from 4.x.

## The model in one example

```scala
import java.time.LocalDate
import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

object Orders extends MessageFamily("order"):          // key prefix, English source
  val cancelled = message("Your order was cancelled", Dutch -> "Uw bestelling werd geannuleerd")
  val shipped   = message[(count: Int, on: LocalDate)](
    ".match {$count :plural} when one {One item shipped on {$on :date dateStyle=long}} " +
      "when * {{$count} items shipped on {$on :date dateStyle=long}}"
  )

given Catalogues = Catalogues(Seq(Orders))             // the app's registry
Orders.shipped(count = 3, on = today).localize(Dutch)  // falls back to English, formats in Dutch
```

- `MessageFamily(prefix, sourceLanguage = English)`; each message a `val` = `message(...)`.
- No type argument: a `Message`. A named tuple of argument types: a definition applied by name or
  position, checked by the compiler.
- `Displayable#localize(language)(using Catalogues)` shows a `Message`, a `Text` or a legacy
  message. Fallback: exact, subtags removed, configured fallbacks, source language. Never throws.
- A `Language` is a BCP 47 tag on every platform. `Language.fromLocale(java.util.Locale)` and
  `language.locale` (both JVM only) convert from and to the JDK's locale; `Language.preferred`
  (both platforms) reads the platform's preferred languages. See core.md.
- Translations: inline pairs (in the Scala.js bundle!) or JSON catalogue files.

## Reference index - read the file that matches the task

- **`reference/core.md`**: `Language`, `MessageFamily`, `message`, the macro's checks,
  `MessageArg`, `Message`, `Text`, `Displayable`, `MessageKey`, the JSON format, the jsoniter-scala
  and zio-json codecs, the bounds and the key rules.
- **`reference/catalogues.md`**: catalogue files, the `Catalogues` registry and its chain, the
  classpath (JVM), `CatalogueLoader` (Scala.js), `verify`, the export tool.
- **`reference/messageformat2.md`**: the template syntax, functions, `.match`, `validate`.
- **`reference/coremessages.md`**: the ready-made families.
- **`reference/formatting.md`**: platform behaviour, cross-platform rules, build and layout.
- **`reference/migration.md`**: moving from 5.x to 6.0.0 (the JSON codecs, the zio-json module,
  ZIO leaving the dependencies, `Language.locale`, the wire differences), and from 4.x,
  constructor by constructor.

## Top gotchas

- `message` works only as the right-hand side of a `val` in the family's body, with a literal
  template; a `def`, a nested object or a class is a compile error. Use a plain `val`, not a
  `lazy val` (it joins `definitions` only when first used, unseen by verify, the export and
  JSON decoding until then). The key is the val's name:
  renaming the val changes the key, which is a wire contract.
- A key is a contract: never reuse it for other arguments. On the wire, adding an argument needs
  a new key unless it is an `Option`. In a family a library publishes, any change to a
  definition's arguments (an `Option` too) is a breaking change: add a new val instead.
- Decoding a `Message` from JSON needs a `given Catalogues` that knows the family (`Catalogues(Seq(...))`
  or `register`). An unknown key reads as its `text`. The codecs are jsoniter-scala's
  `JsonValueCodec` givens in the companions (encoding a `Message` asks for a `Catalogues` too;
  pass `Catalogues()` when only writing). For zio-json, depend on `taihei-tsuji-zio-json` and
  `import dev.taihei.tsuji.ziojson.TsujiZioJson.given`; Tsuji 6 has no zio-json givens in its
  companions.
- Inline translations are code and end up in the Scala.js bundle; a browser app with many
  languages serves exported files and loads them with `CatalogueLoader`.
- Completeness of files is checked by `Catalogues.verify` (or `ClasspathCatalogues.verify`) in
  tests, not by the compiler. Mark the languages that must be complete as `required`.
- The functions of a user `MessageArg.as` type are checked by `verify`, not the macro.
- `legacy.Message` and `dev.taihei.tsuji.Message` share a simple name: an explicit
  `import dev.taihei.tsuji.legacy.Message` wins over `dev.taihei.tsuji.*`.
- `shared/` compiles on Scala.js too: no JVM-only API there.
- French plural `one` is 0 and 1; English and Dutch only 1.
