# Release 6.0.0

**Release Date:** 2026-11-03

Taihei Tsuji 6.0.0 is a major release. Its JSON codecs are now
[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala)'s, written by hand on its token
API, and the zio-json codecs move to a new module, `taihei-tsuji-zio-json`. ZIO, zio-streams and
zio-json therefore leave the core's dependencies: an application that does not use ZIO no longer
gets it through Tsuji. The JSON on the wire and in catalogue files stays what 5.x writes and
reads, apart from the few cases listed under [JSON reading and writing](#json-reading-and-writing).

6.0.0 also adds factories that create a `Language` from the platform's locale, and moves
`Language.locale` off the shared class, so that `Language` holds no platform type on any
platform. `legacy.Message` stays, deprecated, until 7.0.0.

## Highlights

- **No ZIO in the core.** `taihei-tsuji` depends on jsoniter-scala-core and, on Scala.js,
  scala-java-time (with its tzdb), and on nothing else at run time.
- **jsoniter-scala codecs in the companions**: `JsonValueCodec` givens for `Language`,
  `MessageKey`, `Text`, `Message`, `Displayable` and `legacy.Message`, and `JsonKeyCodec` givens
  for `Language` and `MessageKey`, with no import.
- **`taihei-tsuji-zio-json`**, a new artifact for the JVM and Scala.js: zio-json codecs for the same
  types in one object, `TsujiZioJson`. They write the same bytes as the core and read through it.
- **`Language` from the platform locale**: `Language.fromLocale(java.util.Locale)` on the JVM,
  `Language.preferred` on both platforms, and `language.script` and `language.region`.
- **`Language.locale`** is an extension method on the JVM only; it is gone on Scala.js.

## Artifacts

One signed bundle holds five artifacts, all at 6.0.0:

| Artifact | Platform | |
| --- | --- | --- |
| `dev.taihei:taihei-tsuji_3` | JVM | |
| `dev.taihei:taihei-tsuji_sjs1_3` | Scala.js | |
| `dev.taihei:taihei-tsuji-zio-json_3` | JVM | new |
| `dev.taihei:taihei-tsuji-zio-json_sjs1_3` | Scala.js | new |
| `dev.taihei:taihei-tsuji-tools_3` | JVM | |

```scala
// Mill
mvn"dev.taihei::taihei-tsuji:6.0.0"
mvn"dev.taihei::taihei-tsuji-zio-json:6.0.0"   // only if you use zio-json
// sbt
"dev.taihei" %%% "taihei-tsuji" % "6.0.0"
"dev.taihei" %%% "taihei-tsuji-zio-json" % "6.0.0"
```

## Dependencies

- `taihei-tsuji`: `com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core` 2.42.0 (MIT), pinned
  to that exact version; on Scala.js also scala-java-time and scala-java-time-tzdb 2.8.0. No
  `dev.zio` artifact, in any scope. A build check resolves the published POMs and fails if one
  appears.
- `taihei-tsuji-zio-json`: `taihei-tsuji` 6.0.0 and `dev.zio::zio-json` 1.1.1 (Apache 2.0), which
  brings zio and zio-streams. Its tests ran against zio-json 1.1.1, the newest on Maven Central
  when this release was prepared.
- `taihei-tsuji-tools`: `taihei-tsuji` 6.0.0. It writes catalogue files byte-equal to those of
  5.0.x.

If your code uses ZIO, `zio.Chunk` or zio-json without depending on them itself, because Tsuji
brought them, add the dependency.

## Breaking changes

The release is a major: there is no MiMa baseline. The public API was compared with 5.0.2's by
hand, and MiMa run against 5.0.2 reports exactly the removals below and the move of
`Language.locale`, apart from members that were never public.

### JSON codecs

**Removed** from the companions, the zio-json givens:

- `Language`: `JsonEncoder`, `JsonDecoder`, `JsonCodec`, `JsonFieldEncoder`, `JsonFieldDecoder`.
- `MessageKey`: `JsonEncoder`, `JsonDecoder`, `JsonFieldEncoder`, `JsonFieldDecoder`.
- `Text`: `JsonEncoder`, `JsonDecoder`, `JsonCodec`.
- `Displayable`: `JsonEncoder`, `JsonDecoder`.
- `Message`: `Message.encoder(withText)`, `JsonEncoder`, `JsonDecoder`, `JsonCodec`.
- `legacy.Message`: `JsonCodec`, and the codecs of `legacy.MessageArg`.

**Added**:

- `given JsonValueCodec[Language]` and `given JsonKeyCodec[Language]`.
- `given JsonValueCodec[MessageKey]` and `given JsonKeyCodec[MessageKey]`.
- `given JsonValueCodec[Text]`.
- `Message.codec(withText: Boolean = true)(using Catalogues): JsonValueCodec[Message]` and
  `given (using Catalogues): JsonValueCodec[Message]`.
- `given (using Catalogues): JsonValueCodec[Displayable]`.
- `given JsonValueCodec[legacy.Message]`.

**Changed behaviour:**

- A jsoniter-scala codec is one type for both directions, so writing a `Message` or a
  `Displayable` also asks for a `Catalogues` in scope. A program that only writes passes
  `Catalogues()`.
- A decoding error is a `JsonReaderException`, with other texts than zio-json's. The codecs throw
  nothing else on any input, and `Catalogue.parse` still returns a `Left`.
- Tsuji's values are always written compactly, also inside a `WriterConfig` that indents or sets
  `escapeUnicode`.
- Given names change with their types, for code that names one: for example
  `given_JsonCodec_Language` becomes `given_JsonValueCodec_Language`.
- zio-json, zio and zio-streams are no longer on the classpath through `taihei-tsuji`.

**Migration, for users of zio-json:**

1. Add `dev.taihei::taihei-tsuji-zio-json:6.0.0` next to `taihei-tsuji`.
2. Add `import dev.taihei.tsuji.ziojson.TsujiZioJson.given` where a zio-json codec of a Tsuji type
   is summoned or derived, or `export TsujiZioJson.given` in a codec object of your own.
3. `Message.encoder(withText = false)` becomes `TsujiZioJson.messageEncoder(withText = false)`.
4. If your code used ZIO, `zio.Chunk` or zio-json without depending on them, add your own
   dependency.

**For users of jsoniter-scala:** Tsuji's types have `JsonValueCodec` givens in their companions;
no import is needed. Decoding a `Message` or a `Displayable` needs a `Catalogues` in scope, as
before.

### `Language.locale`

`Language.locale` moves from the shared class to an extension method in the JVM companion.

- **Scala on the JVM:** `language.locale` compiles unchanged, with no new import.
- **`import Language.*`** now also brings `preferred` and, on the JVM, `locale` and `fromLocale`
  into scope, so an unqualified name of your own among these in that scope (an inherited member or
  an outer definition) can become ambiguous: qualify it (`this.locale`, or the owner's name), or
  import only the names you use. `language.script` and `language.region` are new members, and a
  member wins over an extension method: an extension of your own with either name is no longer
  called on a `Language`.
- **Scala.js:** `language.locale` no longer compiles. Use `language.tag`, or
  `java.util.Locale.forLanguageTag(language.tag)`.
- **Java:** `language.locale()` becomes `Language.locale(language)`, a static method.
- **Binary, JVM and Scala.js:** the instance method `Language.locale()` is gone. A library
  compiled against 5.x that calls it fails, with a `NoSuchMethodError` on the JVM and a linking
  error on Scala.js, until it is compiled again against 6.0.0.

## JSON reading and writing

The JSON of messages, texts, displayables, catalogues and the 4.x message shape is unchanged. 6.0.0
writes the same bytes as 5.0.x and reads the same values, with these exceptions.

**Writing**

- A string that contains an unpaired surrogate (a UTF-16 code unit from U+D800 to U+DFFF without
  its pair) is written with U+FFFD, the replacement character, in its place. 5.0.x wrote the code
  unit as it was; it is not valid Unicode and cannot be encoded as UTF-8. Writing never throws.
- A number whose unscaled value is -9223372036854775808, such as a `Long` argument holding
  `Long.MinValue`, is written as Java's `BigDecimal.toString` writes it, for example
  `-9223372036854775808` for the `Long`. 5.0.x on the JVM wrote these numbers with a wrong
  exponent (`-9223372036854775808E-16` for the `Long`), so they read back as a different number;
  JSON already written that way reads as the number it states. 5.0.x on Scala.js wrote the `Long`
  correctly, but a decimal with this unscaled value and digits after the point as text that is not
  a JSON number (`-0.-9223372036854775808` for -922337203685477580.8).

**Reading**

6.0.0 reads the JSON grammar of RFC 8259 strictly. It refuses this input, which 5.0.x read:

- A string escape of an unpaired surrogate, such as `"\ud800"`, `"\udc00"` or `"\udc00\ud800"`. A
  catalogue that holds one is refused as a whole, like any catalogue that is not valid JSON.
- A number with a leading zero (`01`, `-01`, `00`), or with a decimal point that has no digit after
  it or none before it (`1.`, `1.e5`, `-.5`). Tsuji has never written these forms.
- Anything other than whitespace after the top-level value, such as `{"key":"a.b"}x` or
  `{"key":"a.b"} {}`. This applies in `Catalogue.parse`, and when you read with jsoniter-scala's
  default `ReaderConfig`; `withCheckForEndOfInput(false)` leaves it to you.
- A string longer than your `ReaderConfig.maxCharBufSize` (4,194,304 characters by default).
  `Catalogue.parse` reads every catalogue of up to 4 Mi characters as before.

One input reads to a different value: jsoniter-scala's `readFromString` encodes the `String` as
UTF-8 before it reads it, so an unpaired surrogate character in the `String` itself (not an escape)
reads as `?`. `Catalogue.parse(String)` reads the same way. Input read from bytes cannot hold such
a character.

In `taihei-tsuji-zio-json`, zio-json's readers of bytes and streams replace malformed UTF-8 with
U+FFFD before Tsuji sees the input, so the module reads such input with U+FFFD in place, as 5.0.x
did; the core's codecs reading bytes refuse it. To refuse malformed UTF-8 with the module, decode
the bytes to a `String` with a decoder that reports errors before zio-json reads it, or read the
bytes with the core's codecs. The module reads a value whole, with no length bound on its strings,
so bound the input you read.

The bounds 5.0.x puts on reading messages, texts and catalogues hold unchanged in 6.0.0, in the
core's codecs and in the module, under any `ReaderConfig` a caller reads with.

## Mihon

Taihei Mihon 5.0.0 also runs with Tsuji 6.0.0. Every reference from Mihon 5.0.0's classes into
`dev.taihei.tsuji` resolves in Tsuji 6.0.0's classes, checked on the JVM bytecode and on the
Scala.js IR: Mihon 5.0.0 uses only `Language`, `Language(tag)`, `Language.English` and
`language.tag`. So both orders of upgrading work. Mihon 5.0.0 declares zio-json itself, so it keeps
zio-json and ZIO on the classpath; with Mihon 6.0.0, the Mihon release built on Tsuji 6.0.0,
they are gone.

## Added

- `Language.fromLocale(java.util.Locale): Either[String, Language]`, on the JVM:
  `Language.parse(locale.toLanguageTag)`, and a `Left` for a locale without a language
  (`Locale.ROOT`, `und`).
- `Language.preferred: Vector[Language]`, on both platforms: the platform's preferred languages,
  most preferred first (on the JVM the default display locale; in JavaScript `navigator.languages`,
  `navigator.language`, then the locale of `Intl.DateTimeFormat`). Tags that name no language, are
  not well formed or repeat are left out, so the list may be empty.
- `language.script` and `language.region`: the script and region subtags as `Option[String]`.
- The jsoniter-scala codecs and `taihei-tsuji-zio-json`, above.

## Unchanged

- The catalogue files under `META-INF/taihei-tsuji/`, and the files `taihei-tsuji-tools` exports.
- `legacy.Message` stays, deprecated, until 7.0.0.
- The rules for message keys on the wire, and format 1 of catalogue files.

## Support

- Tsuji 5.0.x receives security fixes for 12 months after the release of 6.0.0, on branch `5.0.x`.
- taihei-i18n 4.0.x keeps its 12 months from the release of Tsuji 5.0.0.

## Where to get it

6.0.0 is not on Maven Central, which has Tsuji's versions up to 5.0.2: it is published to the
Taihei Framework's own Maven repository. The GitHub Release of `v6.0.0` holds the signed bundle
and its `SHA256SUMS`, the SHA-256 of every file. Every jar and POM is signed by the Taihei
Framework release key (fingerprint `5026 B299 6DDB 697E C463  0BCF 14A7 33E2 A507 702F`).

## Changelog

### Breaking
- The zio-json givens leave the companions of `Language`, `MessageKey`, `Text`, `Displayable`,
  `Message` and `legacy.Message`; `Message.encoder` and the `legacy.MessageArg` codecs are gone.
- `Language.locale` is a JVM-only extension method; the instance method is gone.
- zio-json, zio and zio-streams are no longer dependencies of `taihei-tsuji`.

### Added
- jsoniter-scala `JsonValueCodec` and `JsonKeyCodec` givens, `Message.codec(withText)`.
- `taihei-tsuji-zio-json` with `TsujiZioJson`, for the JVM and Scala.js.
- `Language.fromLocale`, `Language.preferred`, `language.script`, `language.region`.

### Changed
- Tsuji's own JSON reader and writer on jsoniter-scala-core's token API, without recursion, every
  member read and checked, compact output whatever the caller's `WriterConfig`.
- The JSON differences listed above.
- `legacy.Message` is removed in 7.0.0, not in 6.0.0.

### Build
- Golden files written by the released 5.0.2 jars (5.0.1 wrote the same files) prove that 6.0.0
  writes the same bytes and reads the same values, on the JVM and on Scala.js, apart from the
  differences listed above; `make validate` runs them.
- `make no-zio` checks that no published artifact but `taihei-tsuji-zio-json` names a `dev.zio`
  module, and that the module names zio-json at its pin.
- `make zio-json-newest` runs the module's tests against the newest zio-json on Maven Central.
- `release.majorLine` is 6.0.0; `taihei-tsuji-zio-json` has its own MiMa floor at 6.0.0.

**Full Changelog**: https://github.com/taiheiframework/tsuji/compare/v5.0.2...v6.0.0
