# Release 5.0.2

**Release Date:** 2026-10-27

Taihei Tsuji 5.0.2 is a security release. It fixes the advisory
[GHSA-pjj4-5gjj-j2p8](https://github.com/taiheiframework/tsuji/security/advisories/GHSA-pjj4-5gjj-j2p8)
(Moderate): with untrusted JSON, the decoders of `Displayable`, `Text` and `Message`, and
`Catalogue.parse`, could throw instead of returning `Left`, take a long time, or read a number as
a different value. It affects `taihei-tsuji` 5.0.0 and 5.0.1 on the JVM and on Scala.js; see the
advisory for the details. `taihei-i18n` 4.x is not affected.

Everyone who decodes messages, texts or catalogues from JSON they do not control should upgrade.
5.0.2 changes no public API and is binary and source compatible with 5.0.1.

## What changes

The JSON decoders of `Displayable`, `Text` and `Message`, and `Catalogue.parse`, now read JSON
with a reader that does not recurse, and refuse the following with a decoding error (a `Left`).
Valid messages, texts and catalogues read as before.

- **Depth:** JSON in a message or a text nested deeper than 64 levels of arrays and objects. A
  message the library writes needs at most about 25 levels. A catalogue keeps its limit of 16.
- **Number length:** a number longer than 1100 characters, anywhere in a message, a text or a
  catalogue, including under fields the decoder ignores.
- **Exponent:** a number whose exponent has more than 9 digits, leading zeros not counted.
  `1e1000000000` is refused; `1e+000999999999` is read.
- **Built trees:** a JSON tree (`Json`) built by the caller or another reader and decoded with
  `.as[...]` is refused if it nests deeper than 64 levels, or holds a number whose scale is beyond
  1000001100 either way. No number read from text within the bounds above has such a scale.
- **Catalogue files:** `Catalogue.parse`, `ClasspathCatalogues.read` and the JVM's lazy loading of
  catalogue files apply the same number bounds. A refused file is a `CatalogueProblem`, and a
  message whose catalogue is refused falls back as for any missing translation.

## Upgrading

Change the version to 5.0.2:

```scala
mvn"dev.taihei::taihei-tsuji::5.0.2"        // Mill
"dev.taihei" %%% "taihei-tsuji" % "5.0.2"   // sbt, JVM and Scala.js
```

`taihei-mihon` 5.0.0 depends on `taihei-tsuji` 5.0.0: if you use Mihon, depend on `taihei-tsuji`
5.0.2 directly until a Mihon release moves its pin.

If you cannot upgrade yet: bound the size of untrusted JSON before decoding, and catch `NonFatal`
around the decode and around `Catalogue.parse`.

## Other changes

- The release machinery of the 5.0.x line is now the same as main's (a security release goes to
  Maven Central first; its GitHub Release follows from Central's files). Nothing in the published
  artifacts or their POMs changes because of it.

## Module Changes

- `taihei-tsuji` (JVM and Scala.js): the decoders and `Catalogue.parse` above.
- `taihei-tsuji-tools`: no source changes, released at the same version.

## Compatibility

Scala 3.9.0, Scala.js 1.22.0, zio-json 1.1.1, Java 17 or later on the JVM, as in 5.0.1. Binary and
source compatible with 5.0.1 (MiMa).
