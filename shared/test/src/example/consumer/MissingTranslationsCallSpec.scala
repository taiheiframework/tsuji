package example.consumer

import scala.annotation.nowarn

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.CoreMessages.Validation

// An application outside the library's package: the calls written for 6.0.x with a joined
// string still compile, now with a deprecation warning, and pick the old message; a list of
// languages picks the new one, by name or by position.
object MissingTranslationsCallSpec extends ZIOSpecDefault:

  def spec = suite("Validation.missingTranslations from an application")(
    test("the 6.0.x calls with a joined string still compile and show the string as it is") {
      given Catalogues = Catalogues(CoreMessages.families)
      @nowarn("cat=deprecation")
      val named      = Validation.missingTranslations(languages = "nl, fr")
      @nowarn("cat=deprecation")
      val positional = Validation.missingTranslations("German")
      @nowarn("cat=deprecation")
      val key        = Validation.missingTranslations.key.value
      assertTrue(
        named.localize(Language.English) == "missing translations for: nl, fr",
        positional.localize(Language.English) == "missing translations for: German",
        key == "taihei.validation.missingTranslations",
        named.key.value == key,
      )
    },
    test("a list of languages takes the new message, by name or by position") {
      given Catalogues = Catalogues(CoreMessages.families)
      val named      = Validation.missingTranslations(languages = List(Language.Dutch))
      val positional = Validation.missingTranslations(List(Language.Dutch, Language.English))
      assertTrue(
        named.key.value == "taihei.validation.missingTranslationsFor",
        named.localize(Language.English) == "missing translations for: Dutch",
        positional.localize(Language.English) == "missing translations for: Dutch, English",
        Validation.missingTranslations(Nil).localize(Language.English) == "missing translations for: ",
      )
    },
  )

end MissingTranslationsCallSpec
