# migration - from Tsuji 5.x to 6.0.0, and from taihei-i18n 4.x to Tsuji 5.x

## From Tsuji 5.x to 6.0.0: `Language.locale`

The class `Language` holds no `java.util.Locale` any more; `locale` is a JVM-only extension method
in the companion.

| Caller | 5.x | 6.0.0 |
| --- | --- | --- |
| Scala on the JVM | `language.locale` | unchanged, no import needed (but see the note on `import Language.*`) |
| Scala.js | `language.locale` | does not compile: `language.tag`, or `java.util.Locale.forLanguageTag(language.tag)` |
| Java | `language.locale()` | `Language.locale(language)` |
| Binary (JVM and Scala.js) | instance method `Language.locale()` | gone: a library compiled against 5.x that calls it fails (`NoSuchMethodError` on the JVM, a linking error on Scala.js) until it is rebuilt |

`import Language.*` also brings `locale` into scope; an unqualified `locale` of the caller's own
(inherited, or from an outer scope) can become ambiguous there. Qualify it (`this.locale` for an
inherited member, the owner's name for an outer one), or import only the names used.

## Step 0: the new name

Taihei Tsuji 5.0.0 is the successor of `dev.taihei::taihei-i18n` 4.x under a new name: the
dependency becomes `dev.taihei::taihei-tsuji` (and `taihei-tsuji-tools`), the package
`dev.taihei.i18n` becomes `dev.taihei.tsuji`, and catalogue files move from
`META-INF/taihei-i18n/` to `META-INF/taihei-tsuji/`. The JSON of a message and the keys of
`CoreMessages` stay the same. There is no taihei-i18n 5.0.0 and no relocation: the two lines are
different artifacts in different packages and can share a classpath, but their `Language` types
are different types.

```sh
grep -rlZ 'dev\.taihei\.i18n' --include='*.scala' . | xargs -0 perl -pi -e 's/dev\.taihei\.i18n/dev.taihei.tsuji/g'
```

## Step 1: imports only

The 4.x `Message` is `dev.taihei.tsuji.legacy.Message` in 5.x, unchanged and deprecated, until
6.0.0:

```scala
import dev.taihei.tsuji.*
import dev.taihei.tsuji.legacy.Message   // explicit, so it wins over the wildcard's Message
```

Code that only builds and translates 4.x messages then compiles with deprecation warnings; code
that uses `Message.of`, `Language.label`, `CoreMessages`, the `MessageArg` union or the MF2
internals needs the changes of step 2. To silence them while migrating:
`-Wconf:cat=deprecation&origin=dev\.taihei\.tsuji\.legacy\..*:s`. Legacy messages are
`Displayable` and show next to new ones; `legacy.MessageArg` is the 4.x union type.

## Step 2: families, one at a time

| 4.x | 5.x |
| --- | --- |
| `Message.with0Args(English -> "Saved", Dutch -> "Opgeslagen")` | `val saved = message("Saved", Dutch -> "Opgeslagen")` in a `MessageFamily` |
| `Message.withNamedArgs(English -> "Hello {$name}")("name" -> n)` | `val hello = message[(name: String)]("Hello {$name}")`; `hello(name = n)` |
| `with1Arg` ... `with10Args`, `{$0}` | named arguments in the named tuple |
| `translate(l)`, `render(l)` | `localize(l)` with a `given Catalogues` |
| `toString` (English) | `toString` (the family's source language) |
| `Message.of(throwable)` | `Text.of(throwable)` |
| `Message.of(string)` | `Text(string, Language.English)` |
| `MessageFormat2.Parser`, AST, `Context` | internal; `format` and `validate` |
| `PlatformNumberFormatter` and the other `Platform*` objects | internal; format in a template |
| `Language.label` (legacy) | `Language.label: Message` |
| `CoreMessages.Exceptions.revisionMismatch(expected, actual)` | `revisionMismatchBetween(expected, actual)` |
| `CoreMessages.Validation.formatErrors(Map[String, List[Message]])` | `formatErrors(Map[String, List[Displayable]])` |
| CoreMessages in Dutch and French on Scala.js (inline in 4.x) | files now: export with `--scan dev.taihei.tsuji` and load with `CatalogueLoader`, or a browser shows English |
| the case class `Message(args, namedArgs, translationTemplates)`, `copy`, fields | only on `legacy.Message` |
| `nonEmpty` | `localize(language).nonEmpty` |
| `given JsonCodec[Message]` needing nothing | the decoder and codec need a `given Catalogues` where summoned or derived |
| `!!unknown message!!` | gone: the source language always has the key |

## JSON during a rollout

5.x reads 4.x JSON (as a legacy message with the key `legacy`), within the bounds of
taihei-i18n 4.0.x: at most 1000 templates and 1000 arguments, none of them, nor an argument name,
longer than 65536 characters. `legacy.Message`'s own codec holds to the same bounds. A 4.x
reader cannot read a 5.x message: upgrade readers (clients) first, or keep writing legacy
messages until they have.

## Libraries built on 4.x

A library compiled against taihei-i18n 4.x (taihei-lorem 4.x, for one) keeps working beside
Taihei Tsuji 5, with its own taihei-i18n jar: the packages differ, so nothing clashes. Its
`dev.taihei.i18n.Language` is not the `dev.taihei.tsuji.Language` of 5.x, so the two cannot be
passed for each other. `Language` has the API of 4.x apart from `label`, which returns the new
`Message`.
