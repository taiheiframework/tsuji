package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.TestJson.*

object LanguageSpec extends ZIOSpecDefault:

  def spec = suite("LanguageSpec")(
    suite("Language enumeration")(
      test("English is the default language") {
        assertTrue(
          Language.English.isDefault,
          Language.English == Language.defaultLanguage,
        )
      },
      test("Dutch is not the default language") {
        assertTrue(!Language.Dutch.isDefault)
      },
      test("French is not the default language") {
        assertTrue(!Language.French.isDefault)
      },
      test("English has ISO code 'en'") {
        assertTrue(Language.English.isoCode == "en")
      },
      test("Dutch has ISO code 'nl'") {
        assertTrue(Language.Dutch.isoCode == "nl")
      },
      test("French has ISO code 'fr'") {
        assertTrue(Language.French.isoCode == "fr")
      },
    ),
    suite("Language.of()")(
      test("returns correct language for valid ISO code") {
        assertTrue(
          Language.of("en") == Language.English,
          Language.of("nl") == Language.Dutch,
          Language.of("fr") == Language.French,
        )
      },
      test("returns default language for invalid ISO code") {
        assertTrue(
          Language.of("invalid") == Language.defaultLanguage,
          Language.of("") == Language.defaultLanguage,
        )
      },
    ),
    suite("Language factory methods")(
      test("empty returns English") {
        assertTrue(Language.empty == Language.English)
      },
      test("sample returns a valid language") {
        val lang = Language.sample
        assertTrue(
          lang == Language.English || lang == Language.Dutch || lang == Language.French
        )
      },
    ),
    suite("Language JSON encoding/decoding")(
      test("Language can be encoded to JSON") {
        val json = Language.English.toJson
        assertTrue(json.nonEmpty)
      },
      test("Language can be decoded from JSON") {
        val encoded = Language.Dutch.toJson
        val decoded = encoded.fromJson[Language]
        assertTrue(decoded == Right(Language.Dutch))
      },
      test("All languages can round-trip through JSON") {
        val languages = List(Language.English, Language.Dutch, Language.French)
        val results   = languages.map { lang =>
          val encoded = lang.toJson
          val decoded = encoded.fromJson[Language]
          decoded == Right(lang)
        }
        assertTrue(results.forall(identity))
      },
    ),
    suite("Language as Map key")(
      test("languages sort by their tags") {
        val sorted = List(Language.French, Language.Dutch, Language("nl-BE"), Language.English, Language("de")).sorted
        assertTrue(sorted.map(_.tag) == List("de", "en", "fr", "nl", "nl-BE"))
      },
      test("Languages can be used as Map keys") {
        val map = Map(
          Language.English -> "Hello",
          Language.Dutch -> "Dag",
          Language.French -> "Bonjour",
        )
        assertTrue(
          map(Language.English) == "Hello",
          map(Language.Dutch) == "Dag",
          map(Language.French) == "Bonjour",
        )
      },
    ),
  )

end LanguageSpec
