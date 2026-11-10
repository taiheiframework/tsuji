# catalogues - Catalogue, Catalogues, loading, verify, export

Package `dev.taihei.tsuji`; the export is in `taihei-tsuji-tools`.

## Catalogue files

One JSON file per language, MF2 templates by key:

```json
{"format":1,"language":"de","messages":{"order.shipped":"...","order.cancelled":"..."}}
```

A file is UTF-8 without a byte order mark. The classpath reader (JVM) and `CatalogueLoader`
(Scala.js) refuse a file whose bytes start with EF BB BF with the problem
`<file> starts with a byte order mark; save it as UTF-8 without one`; the export never writes
one.

- `Catalogue(language, messages: Map[MessageKey, String])`, `catalogue.toJson`.
- `Catalogue.parse(json): Either[CatalogueProblem, Catalogue]` never throws. It refuses a file
  over 4M characters, nested deeper than 16, with a number longer than 1100 characters or with
  an exponent of more than 9 digits, with more than 100000 messages, with more than 16 keys that
  share one hash code, not JSON (read strictly, nothing but whitespace after the value), not format 1,
  or without a valid `language`. Entries
  that are not a key and a string template of at most 64K characters are left out and listed in
  `catalogue.problems`.
- `CatalogueProblem` is read, not built: `message`, `language: Option[Language]`,
  `key: Option[MessageKey]`; input text in
  it is escaped and cut short, safe to log.

## Catalogues, the registry

```scala
given catalogues: Catalogues = Catalogues(
  families = Seq(Orders) ++ CoreMessages.families,
  fallbacks = Map(Language("lb") -> Seq(Language("de"))),
)
Orders.cancelled.localize(Language("lb-LU"))
```

- The chain for language L: L, L without its extensions and private use, then without its last
  subtag step by step, the configured fallbacks of each of those (walked the same way), the
  family's source language. Numbers and dates always formatted for L; a plural variant chosen by
  the rules of the template's own language (since 6.1.0).
  `catalogues.fallbackChain(language, source)` shows it.
- In a language, a catalogue file wins over an inline translation; in the source language the
  code's template wins.
- `add(catalogue): List[CatalogueProblem]`: later adds win. Templates of known families are checked
  now, others when their message is first shown; one that fails is dropped and falls back.
- `register(families*)`: needed for decoding JSON; showing a message registers its family.
- `languages`, `problems` (the first 1000, each once: failed checks, a template that could not be
  formatted with its language and the kind of cause, a throwing listener),
  `onAdded(listener): () => Unit` (returns the unsubscribe; a throwing listener does not stop the
  others and is named in `problems`).
- Thread-safe. Showing a message never throws.

## JVM: the classpath

Files at `META-INF/taihei-tsuji/<prefix>/<tag>.json` (canonical tag, `zh-Hant.json`), read with the
family's class loader the first time a language is needed, at most 16 MB, UTF-8 only. A file
found is kept; of the misses the latest 4096 are remembered. Narrow a request header's language
to the supported ones. Keys of
another family in a family's file are left out with a problem.

- `ClasspathCatalogues.read(family, language): Option[Either[CatalogueProblem, Catalogue]]`.
- `ClasspathCatalogues.verify(families, languages, required): List[CatalogueProblem]`.

## Scala.js: fetch

```scala
CatalogueLoader.load(catalogues, Language("de"), "/i18n/de.json"): Future[List[CatalogueProblem]]
CatalogueLoader.load(catalogues, Language("de"), "/i18n/de.json", timeout = Some(10.seconds)) // 6.1.0
```

Takes a `given ExecutionContext`, uses the global `fetch`, never fails the `Future`. Needs a
`TextDecoder` that honours `fatal` and `ignoreBOM` (every current browser and Node); with one that
ignores them every load is a problem. `timeout` (`Option[FiniteDuration]`, default `None`, since
6.1.0): a file not arrived in full by then is the problem `cannot fetch <url>: timed out after
<timeout>`, the request is aborted and its body let go; without it a slow server keeps the load
waiting. Pass one in an application. Until a language is loaded, messages show through the
chain; `onAdded` tells when to re-render. No dependency on Laminar or an effect library. Inline
translations are in the bundle: a browser application with many languages uses files.

## verify, for tests

```scala
Catalogues.verify(families, catalogues, required = Set(Language.Dutch)): List[CatalogueProblem]
```

Empty means all is well. It checks every template (code, inline, files) against its definition,
user argument types included; keys without a definition; every required language complete (inline
or file); duplicate prefixes and keys; a language given twice inline; an inline translation in the
source language; and (since 6.1.0) a `:plural` key the template's language never selects, such as
`few` in English, which is reported but still shown.

## Export: taihei-tsuji-tools

```
dev.taihei.tsuji.tools.ExportCatalogues --out <dir> [--scan <package>]... [--family <object>]...
```

Runs on the JVM, in a module whose classpath holds the families (for a cross-built app, the
JVM side of its shared code). Writes `<dir>/<tag>.json` per language, merging every family found (libraries included): the
source templates for families in that language, else classpath files over inline translations.
Failing templates, and family objects that cannot be loaded, are left out and printed (exit 1);
a `--scan` that finds no family and a classpath jar or directory that cannot be read are printed
too (exit 1); usage errors, and finding no family at all, exit 2. Run as `runMain` (Mill)
or a forked `run` (sbt).
