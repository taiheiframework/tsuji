# core - Language, message families, Message and Text

Package `dev.taihei.tsuji`.

## Language

`Language` is a final class identified by its canonical BCP 47 tag. The three
built-ins are `val`s in the companion; any other language is `Language(tag)`.
(Until 2.0.0 it was a closed enum of the three built-ins.)

```scala
import dev.taihei.tsuji.Language
import dev.taihei.tsuji.Language.*

English                    // the default; also Dutch, French
Language("de")             // any valid tag
Language("nl-BE").base     // Dutch
Language("en") == English  // equality and hash by tag
```

- `.tag` / `.isoCode: String` - the canonical tag (`"en"`, `"nl-BE"`, `"zh-Hant-TW"`).
- `.base: Language` - without script, region or variant (`nl-BE` gives `nl`).
- `.script: Option[String]` / `.region: Option[String]` - the script and region subtags
  (`zh-Hant-TW`: `Hant`, `TW`; `es-419`: region `419`; `de-1996`, `en-x-us`: no region).
- `.locale: java.util.Locale` - JVM only, since 6.0.0 an extension method in the companion (no
  import needed): `Locale.forLanguageTag(language.tag)`, the way back from `fromLocale`. It does
  not exist on Scala.js: use `.tag`. Java calls it as `Language.locale(language)`.
- `.isDefault: Boolean` - true for `English`.
- `.label: Message` - the language's name as a message of the family
  `CoreMessages.Languages`: hand translations for the built-ins, the platform's names
  (JDK locale data, `Intl.DisplayNames` in JS) for any other language, in any reader's
  language (`Language("de").label.localize(Language("ja"))` is `ドイツ語`).
- `.toString` - `"English"`, `"Dutch"`, `"French"` for the built-ins, else the tag.

Companion:

| Member | Result |
| --- | --- |
| `Language(tag: String): Language` | any valid BCP 47 tag of at most 255 characters, canonicalized; `IllegalArgumentException` otherwise |
| `Language.parse(tag: String): Either[String, Language]` | the same without throwing |
| `Language.of(isoCode: String): Language` | the language for a valid tag; `English` only for invalid input |
| `Language.fromLocale(locale: java.util.Locale): Either[String, Language]` | JVM only: `parse(locale.toLanguageTag)`, `Left` for a locale without a language (`Locale.ROOT`, `und`) |
| `Language.preferred: Vector[Language]` | the platform's preferred languages, most preferred first (JVM: the default display locale; JS: `navigator.languages`, `navigator.language`, `Intl`); `und`, invalid tags and repeats left out; may be empty |
| `Language.fromAcceptLanguage(header: String): Vector[Language]` | an HTTP `Accept-Language` value, most preferred first: ordered by `q` (ties in header order), `q=0` and everything weighted below `*` left out (`*` after the entries of its own weight), unreadable entries, `und` and repeats skipped, `null` as empty; cut to 4096 characters (at the last whole entry) and 32 entries; never throws (6.1.0) |
| `Language.negotiate(requested: Seq[Language], supported: Seq[Language], default: Language): Language` | the first supported language for the first requested one that has one, each requested tag walked down as `Catalogues` walks it (exact, without extensions, last subtag removed: `zh-Hant-TW`, `zh-Hant`, `zh`); never broader to narrower (`en` does not pick `en-GB`); configured `Catalogues` fallbacks not consulted; else `default` (6.1.0) |
| `Language.values: Array[Language]` | the built-ins (`English`, `Dutch`, `French`) |
| `Language.valueOf(name: String): Language` | a built-in by name (`"Dutch"`) |
| `Language.defaultLanguage: Language` | `English` |
| `Language.empty: Language` | alias for `English` |
| `Language.sample: Language` | a random built-in (test/sample data) |

A `match` on `Language` is not checked for exhaustiveness: `case English =>` still
works, but add `case _` for every other language. `ordinal` and `fromOrdinal` are
gone.

The class `Language` holds no platform locale type; on the JVM `java.util.Locale` appears only in
the companion (`fromLocale`, `locale`). In JavaScript an `Intl.Locale` is read by its tag:
`Language.parse(intlLocale.toString)`.

Codecs (given, jsoniter-scala): `JsonValueCodec[Language]` and `JsonKeyCodec[Language]` (so
`Language` works as a JSON map key). A built-in is written by its name (`"English"`), any other
language by its tag (`"de"`); both forms are read. The zio-json codecs are in
`taihei-tsuji-zio-json` (see JSON below).

### From a request

```scala
val supported = Seq(Language.Dutch, Language.French, Language.English)
val language  = Language.negotiate(Language.fromAcceptLanguage(header), supported, Language.English)
// "nl-BE,nl;q=0.9" gives Dutch, "fr-CA, *;q=0.1" French, "de" English
```

Narrow a request's language this way before showing messages in it: the output stays in a
language the application supports, and on the JVM each unknown tag would otherwise cost a
classpath lookup per family.

## Message families

```scala
import java.time.LocalDate
import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

object Orders extends MessageFamily("order"):              // prefix; source language English
  val cancelled = message("Your order was cancelled", Dutch -> "Uw bestelling werd geannuleerd")
  val shipped   = message[(count: Int, on: LocalDate)](
    ".match {$count :plural} when one {One item shipped on {$on :date dateStyle=long}} " +
      "when * {{$count} items shipped on {$on :date dateStyle=long}}",
  )

Orders.cancelled                       // a Message, key "order.cancelled"
Orders.shipped(count = 3, on = today)  // a Message, key "order.shipped", two arguments
Orders.shipped(3, today)               // the same, by position
```

- `abstract class MessageFamily(prefix: String, sourceLanguage: Language = English)`. The prefix
  is parts of an ASCII letter followed by letters, digits and `_`, joined by dots; another prefix
  throws `IllegalArgumentException` when the object is first used.
- `protected transparent inline def message[A <: AnyNamedTuple](inline template: String, inline translations: (Language, String)*)`:
  only as the right-hand side of a `val` in the family's body. Without a type argument it is a
  `Message`; with a named tuple it is a `MessageDefinition[A]` whose `apply` takes the arguments
  by name or position, checked by the compiler.
- The key is `prefix + "." + valName`. Keep it stable: it is the wire contract.
- `family.definitions: List[MessageDefinition[?]]`, in order.
- `MessageDefinition`: `key: MessageKey`, `family`, `template` (source), `translations:
  Map[Language, String]` (inline), `argumentNames: List[String]`.

### Compile-time checks (the macro)

For the template and every inline translation that is a literal: valid MF2; every variable an
argument or declared before use; `:number`, `:integer`, `:plural` only on `Int`, `Long`, `Double`,
`Float`; `:datetime` on `LocalDate`, `LocalDateTime`, `LocalTime`; `:date` on `LocalDate` and
`LocalDateTime`; `:time` on `LocalTime` and `LocalDateTime`; a `.match` needs `when * {...}`;
argument names are identifiers. An unused argument is a warning. A template that is not a literal
does not compile. A non-literal inline translation, and the functions of a user type, are checked
by `Catalogues.verify` at test time.

### Arguments: `MessageArg[A]`

Givens for `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`, `LocalDate`, `LocalDateTime`,
`LocalTime`, `Language` (shown as its name in the reader's language, sent as its tag), `Message`,
`Text`, `Displayable` (nested, shown in the reader's language), `Option[A]` (`None` shows as empty
text and may be missing on the wire) and `List[A]` (joined with `, `). Your own type:

```scala
// OrderId.parse: String => Either[String, OrderId], the type's own smart constructor. It reads
// what came over the wire, so never `s => Right(OrderId(s))` for a type that has an invariant.
given MessageArg[OrderId] = MessageArg.as[OrderId, String](_.value, OrderId.parse)
```

## Displayable, Message, Text

- `abstract class Displayable` (not extendable outside the library):
  `localize(language: Language)(using Catalogues): String`.
- `final class Message`: `key: MessageKey`, `localize`, `toString` (source language), equality by
  key and argument values. A message read from 4.x JSON has the key `legacy`.
- `final case class Text(value: String, language: Language)`: shown as it is. `Text.of(throwable)`
  gives the throwable's message in English, or its class name when it has none.
- `opaque type MessageKey`: `MessageKey.parse(s): Either[String, MessageKey]`, `key.value`.

## JSON

| Value | Shape |
| --- | --- |
| `Message` | `{"key":"order.shipped","args":{"count":3,"on":"2026-10-23"},"text":"3 items shipped on October 23, 2026"}` |
| `Text` | `{"text":"Disk full","language":"en"}` |

### jsoniter-scala codecs (the core)

Givens in the companions, no import beyond `com.github.plokhotnyuk.jsoniter_scala.core.*`:

| Type | Givens |
| --- | --- |
| `Language`, `MessageKey` | `JsonValueCodec`, `JsonKeyCodec` |
| `Text` | `JsonValueCodec[Text]` |
| `Message` | `given (using Catalogues): JsonValueCodec[Message]` (with `text`); `Message.codec(withText: Boolean = true)(using Catalogues)` |
| `Displayable` | `given (using Catalogues): JsonValueCodec[Displayable]` |
| `legacy.Message` | `JsonValueCodec[legacy.Message]` |

```scala
import com.github.plokhotnyuk.jsoniter_scala.core.*
given Catalogues = Catalogues(Seq(Orders))
writeToString(Orders.cancelled)                                         // {"key":"order.cancelled","text":"Your order was cancelled"}
readFromString[Message](json)
writeToString(Orders.cancelled)(using Message.codec(withText = false))  // {"key":"order.cancelled"}
```

- One codec type for both directions: writing a `Message` or `Displayable` also needs a
  `Catalogues` (unused when writing); a program that only writes passes `Catalogues()`.
- The key is looked up among the registry's families; an unknown key reads as its `text` (shown
  as is, re-encoded unchanged), and is an error without one. A missing argument is an error unless
  it is an `Option`. The 4.x shape reads as a legacy message.
- A decoding error is a `JsonReaderException`, nothing else is thrown. `Catalogue.parse` returns
  `Left`.
- Written compactly always, whatever the caller's `WriterConfig` (indentation, `escapeUnicode`).
  An unpaired surrogate in a string is written as U+FFFD; writing never throws.
- Reading is strict RFC 8259: an escape of an unpaired surrogate, a number with a leading zero
  (`01`) or a bare decimal point (`1.`, `-.5`) is an error. Content after the value and the
  longest string are the caller's `ReaderConfig` (`checkForEndOfInput`, `maxCharBufSize`).
  `readFromString` turns an unpaired surrogate character in the input `String` into `?`.

### zio-json codecs: `taihei-tsuji-zio-json`

```scala
import zio.json.*
import dev.taihei.tsuji.ziojson.TsujiZioJson.given   // or `export TsujiZioJson.given` in your codec object
Orders.cancelled.toJson
json.fromJson[Message]                                // needs a given Catalogues
Orders.cancelled.toJson(using TsujiZioJson.messageEncoder(withText = false))
```

- `TsujiZioJson` holds `JsonEncoder`/`JsonDecoder`/`JsonCodec` for `Language`, `Text`, `Message`
  (decoder and codec need a `Catalogues`), `JsonEncoder`/`JsonDecoder` for `Displayable` and
  `MessageKey`, `JsonCodec[legacy.Message]`, `JsonFieldEncoder`/`JsonFieldDecoder` for `Language`
  and `MessageKey`, and `messageEncoder(withText)`.
- Same bytes as the core, read through the core: same bounds, same strict grammar. A zio-json AST
  (`.as[Message]`) is checked against the same depth and number bounds (a scale within
  ±1000001100) before it is decoded, and refused when it stands for more than 4194304 values and
  characters, a part placed in several places of the AST counted in each; a `null` in it is
  refused too. The bound holds for each Tsuji value: a collection of N of them in a built AST
  costs up to N times it, so bound what you build.
- A value is read whole and held at a few times its length; strings have no length bound of their
  own. Bound the input (request body limit, `take` on a stream).
- zio-json's byte and stream readers turn malformed UTF-8 into U+FFFD before Tsuji sees it; the
  core's codecs on the same bytes refuse it.

### Bounds and rules

- Limits: the JSON read at most 64 levels of arrays and objects deep and a number in it at most
  1100 characters long with an exponent of at most 9 digits, leading zeros not counted, and a
  mantissa below 256 bits (beyond any of them is a decoding error, on both platforms, under any
  `ReaderConfig`), 8 levels of nested messages, 10000 list elements; the kept arguments of an unknown key at most 16 levels and
  100000 values, with at most 16 names of one object sharing a hash code. Members the decoder does
  not use are read and checked too.
- Rules on the wire: a key is a contract; adding an argument means a new key unless it is an
  `Option`; removing or retyping one means a new key; the envelope is format 1, in 5.x and 6.x.
- Rule in Scala, for a family a library publishes: its definitions' arguments are API. Adding one
  (even an `Option`), removing, renaming or retyping one breaks code compiled against the older
  version at run time, which MiMa cannot see. Within a major, add a new val with a new key.
