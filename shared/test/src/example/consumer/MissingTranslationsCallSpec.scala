package example.consumer

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.CoreMessages.Validation

// An application outside the library's package calls missingTranslations with the languages,
// by name or by position. Up to 6.x it took one string the caller had joined.
object MissingTranslationsCallSpec extends ZIOSpecDefault:

  def spec = suite("Validation.missingTranslations from an application")(
    test("a list of languages, by name or by position") {
      given Catalogues = Catalogues(CoreMessages.families)
      val named      = Validation.missingTranslations(languages = List(Language.Dutch))
      val positional = Validation.missingTranslations(List(Language.Dutch, Language.English))
      assertTrue(
        named.key.value == "taihei.validation.missingTranslationsFor",
        named.localize(Language.English) == "missing translations for: Dutch",
        positional.localize(Language.English) == "missing translations for: Dutch, English",
        Validation.missingTranslations(Nil).localize(Language.English) == "missing translations for: ",
      )
    }
  )

end MissingTranslationsCallSpec
