package dev.taihei.tsuji

import scala.util.Try

import zio.test.*

import dev.taihei.tsuji.TestJson.{*, given}

/**
 * Which strings `Language.parse` accepts as a BCP 47 tag. Up to 4.0.0 the check was the regular
 * expression below; the per-subtag check that replaced it must accept exactly the same tags up to
 * the length bound.
 */
object LanguageTagSpec extends ZIOSpecDefault:

  // The check of 2.0.0 to 4.0.0, verbatim.
  private val OldLangTag =
    ("(?i)[a-z]{2,3}(-[a-z]{3}){0,3}(-[a-z]{4})?(-([a-z]{2}|[0-9]{3}))?" +
    "(-([a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*(-[0-9a-wyz](-[a-z0-9]{2,8})+)*(-x(-[a-z0-9]{1,8})+)?").r

  private def oldAccepts(tag: String): Boolean = OldLangTag.matches(tag.trim.replace('_', '-'))

  // Letters and digits at the edges of each subtag kind, both cases, the singletons x and a, and
  // characters that only look like ASCII letters or that trim and '_' treat specially.
  private val chars =
    Gen.elements('a', 'B', 'x', 'X', 'z', 'q', '0', '1', '9', '_', ' ', 'K', 'ſ', 'é')

  private val subtag = Gen.oneOf(
    Gen.stringBounded(0, 9)(chars),
    Gen.elements(
      "en",
      "nl",
      "zh",
      "abc",
      "Latn",
      "BE",
      "419",
      "1996",
      "abcde",
      "a",
      "x",
      "u",
      "ca",
      "gregory",
      "abcdefghi",
      "1",
      "X",
      "ab",
      "1abc",
      "12345678",
    ),
  )

  private val candidate = Gen.listOfBounded(1, 8)(subtag).map(_.mkString("-"))

  // A valid tag of exactly `length` characters: language, private use, then one-letter parts.
  private def validTagOf(length: Int): String =
    val head = "en-x-abc"
    head + "-a" * ((length - head.length) / 2) + (if (length - head.length) % 2 == 1 then "b" else "")

  private val known = List(
    "en",
    "nl-BE",
    "zh-Hant-TW",
    "es-419",
    "sr-Latn-RS",
    "de-CH-1996",
    "en-US-u-ca-gregory",
    "de-x-private",
    "zh-yue-HK",
    "zh-cmn-Hans-CN",
    "sl-rozaj-biske",
    "en-a-bbb-x-a-ccc",
    "hy-Latn-IT-arevela",
    "x-private",
    "i-klingon",
    "en-",
    "en--US",
    "-en",
    "invalid",
    "toolong-US",
    "en-US-x",
    "en-a",
    "en-a-b",
    "abc-def-ghi-jkl-mno",
    "abc-def-ghi-jkl",
    "en_US",
    " en-GB ",
    "EN-us",
    "en-Latn-Latn",
    "en-US-GB",
    "en-1234",
    "en-a123",
    "en-1-ab",
    "EN-X-A",
    "es-419-1abc",
    "en-u-ab-x-1-a",
    "en-u-ab-v-cd",
    "en-x-a-u-bb",
    "en-u-a-bb",
    "",
  )

  def spec = suite("LanguageTagSpec")(
    suite("the same tags as before")(
      test("known valid and invalid tags") {
        val differing = known.filter(t => Language.parse(t).isRight != oldAccepts(t))
        assertTrue(known.exists(oldAccepts), known.exists(t => !oldAccepts(t)), differing.isEmpty)
      },
      test("generated candidates") {
        check(candidate) { tag =>
          assertTrue(Language.parse(tag).isRight == oldAccepts(tag))
        }
      } @@ TestAspect.samples(5000),
    ),
    suite("canonical form and errors")(
      test("the case of a tag does not depend on the default locale") {
        val turkish = java.util.Locale.forLanguageTag("tr-TR")
        zio.ZIO.acquireReleaseWith(zio.ZIO.succeed {
          val before = java.util.Locale.getDefault
          java.util.Locale.setDefault(turkish)
          before
        })(before => zio.ZIO.succeed(java.util.Locale.setDefault(before))) { _ =>
          zio.ZIO.succeed(
            assertTrue(
              Language("IT").tag == "it",
              Language("en-in").tag == "en-IN",
              Language("az-latn-IR").tag == "az-Latn-IR",
              Language("en-x-PRIV").tag == "en-x-priv",
            )
          )
        }
      },
      test("an invalid tag is echoed with its control characters escaped") {
        val error = Language.parse("en\nUS\u001b[31m").swap.getOrElse("")
        assertTrue(
          error.startsWith("Not a BCP 47 language tag"),
          error.contains("en\\u000aUS\\u001b[31m"),
          error.forall(c => c >= ' ' && c <= '~'),
        )
      },
    ),
    suite("a bound on the length")(
      test("a valid tag of 255 characters is accepted, of 256 refused") {
        val at255 = validTagOf(255)
        val at256 = validTagOf(256)
        assertTrue(
          at255.length == 255,
          at256.length == 256,
          oldAccepts(at256),
          Language.parse(at255).map(_.tag) == Right(at255),
          Language.parse(at256).isLeft,
        )
      },
      test("surrounding whitespace does not count") {
        assertTrue(Language.parse("  " + validTagOf(255) + "  ").isRight)
      },
    ),
    suite("a tag of 100 KB")(
      test("is refused by every entry point without an error") {
        val huge = "en" + "-abcde" * 17000
        val json = "\"" + huge + "\""
        assertTrue(
          huge.length > 100000,
          Language.parse(huge).isLeft,
          Language.parse(huge).swap.exists(_.length < 100),
          Language.of(huge) == Language.English,
          Try(Language(huge)).failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]),
          json.fromJson[Language].isLeft,
          s"{$json: 1}".fromJson[Map[Language, Int]].isLeft,
        )
      },
      test("is refused also when it is well formed up to the end") {
        val huge = "en-x" + "-a" * 50000
        assertTrue(Language.parse(huge).isLeft, Language.parse(huge + "-").isLeft)
      },
    ),
  )

end LanguageTagSpec
