# formatting — cross-platform formatters & build

Package `dev.taihei.i18n`. Locale-aware number/date formatting is abstracted behind two
objects with identical signatures on both platforms, so `shared/` code (and the
`:number` / `:datetime` MessageFormat functions) formats the same way on the JVM
and in the browser.

## The contract (same on JVM and JS)

```scala
object PlatformNumberFormatter:
  def formatNumber(value: Double, style: Option[String], locale: String): String

object PlatformDateTimeFormatter:
  def formatDateTime(value: Any, options: Map[String, String], locale: String): String
```

- `locale` is `"en" | "nl" | "fr"`.
- Number `style`: `Some("percent")`, `Some("currency")`, or `None` (decimal).
- DateTime `options`: e.g. `"dateStyle" -> "short" | "medium" | "long" | "full"`.

| Platform | Numbers | Dates/times | Locale mapping |
| --- | --- | --- | --- |
| **JVM** (`jvm/`) | `java.text.NumberFormat` | `java.time.format.DateTimeFormatter` | `en→Locale.US`, `nl→nl-NL`, `fr→Locale.FRANCE` |
| **Scala.js** (`js/`) | `Intl.NumberFormat` (native facade) | `Intl.DateTimeFormat` (native facade) | `en→en-US`, `nl→nl-NL`, `fr→fr-FR` |

You normally don't call these directly — they're invoked by the `:number` /
`:datetime` annotations in `Message` templates and `MessageFormat2.format`. Call
them directly only when you need a one-off formatted value outside a template.

## Cross-platform rules

- **`shared/src`** must compile on Scala.js — use only `java.time` types backed by
  scala-java-time (`LocalDate`, `LocalDateTime`, `LocalTime` are fine), no
  JVM-only APIs. Anything platform-specific belongs in `jvm/src` or `js/src` as a
  matching `object` with the same signature.
- On JS, `java.time` is provided by `scala-java-time` (+ tzdb for zone data) — both
  are already wired into the `js` module's deps.

## Build & layout

```
shared/src/dev/taihei/i18n/   Language, Message, MessageFormat2, CoreMessages
jvm/src/dev/taihei/i18n/      PlatformNumberFormatter, PlatformDateTimeFormatter (java.*)
js/src/dev/taihei/i18n/       PlatformNumberFormatter, PlatformDateTimeFormatter (Intl)
shared/test/src/dev/taihei/i18n/  specs (run on both platforms)
```

- `make compile` / `make test` cover both platforms (`./mill __.compile`,
  `./mill __.test.testForked` → `i18n.jvm` **and** `i18n.js`; JS tests run on Node).
- Single-platform: `./mill i18n.jvm.test` or `./mill i18n.js.test`.
- Released by the family's release workflow from the maintainer's signed tag, the version
  taken from the git tag, as in the other Taihei Framework libraries.
