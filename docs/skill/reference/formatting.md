# formatting - platforms, cross-platform rules, build

Locale-aware formatting runs through internal platform objects (`private[tsuji]` since 5.0.0): you
format through templates (`:number`, `:integer`, `:datetime`, `:date`, `:time`) in messages or
`MessageFormat2.format`.

| Platform | Numbers | Dates and times | Language names | Catalogues |
| --- | --- | --- | --- | --- |
| JVM (`jvm/`) | `java.text.NumberFormat` | `java.time.format.DateTimeFormatter` | JDK locale data | classpath resources |
| Scala.js (`js/`) | `Intl.NumberFormat` | `Intl.DateTimeFormat` | `Intl.DisplayNames` | `CatalogueLoader` over `fetch` |

- Bare `en`, `nl`, `fr` format as `en-US`, `nl-NL`, `fr-FR`; any other valid tag as written; a tag
  the platform cannot use formats as `en-US`.
- On the JVM a long or full `timeStyle` on `LocalTime`/`LocalDateTime` formats as medium (they have
  no zone); Scala.js prints `UTC`.
- On Scala.js a date after year 275760 or before year -271821 (outside the JavaScript Date range)
  formats as its ISO text, such as `+300000-01-01`; the JVM formats it in the style asked.
- `:number style=currency` always formats USD.

## Cross-platform rules

- `shared/src` compiles on Scala.js: `java.time` through scala-java-time, no JVM-only API.
- Platform differences live in `jvm/src` and `js/src` behind objects with the same signature.

## Build and layout

```
shared/src/dev/taihei/tsuji/          Language, MessageFamily, Message/Text, MessageArg, Catalogue(s), MessageFormat2, CoreMessages, the macro
shared/src/dev/taihei/tsuji/legacy/   the 4.x Message, deprecated until 7.0.0
shared/resources/META-INF/taihei-tsuji/   CoreMessages' Dutch and French catalogues
jvm/src, js/src                      platform formatters; ClasspathCatalogues (JVM), CatalogueLoader (JS)
tools/src                            taihei-tsuji-tools: ExportCatalogues
zio-json/src                         taihei-tsuji-zio-json: TsujiZioJson
```

- `make validate`: scalafix, scalafmt, compile, tests on the JVM and Node, MiMa against the newest
  release of the current major line.
- Released from the Makefile (`make release-prepare TYPE=...`, `make release-finalize`), the
  version from the git tag; one release publishes `taihei-tsuji`, `taihei-tsuji-zio-json` and
  `taihei-tsuji-tools`.
