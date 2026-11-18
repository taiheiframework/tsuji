package dev.taihei.i18n

import zio.test.*

// What `one` takes depends on the language of the locale, which is worked out once per selector:
// the time a `.match` takes does not grow with the length of the locale times its variants.
object PluralLanguageSpec extends ZIOSpecDefault:

  def spec = suite("The language of a plural selector")(
    test("twenty thousand `one` variants with a locale of a million letters") {
      val template = ".match {$n :plural}\n" + "when one {one}\n" * 20000 + "when * {other}"
      val locale   = "x" * 1000000
      TimeBudget.within("20000 one variants, a locale of 1M letters", 2000)(
        MessageFormat2.format(template, Map("n" -> 2), locale)
      ): out =>
        assertTrue(out == "other")
    },
    test("`one` takes 0 in French only, whatever the region") {
      val template                   = ".match {$n :plural}\nwhen one {one}\nwhen * {other}"
      def at(n: Int, locale: String) = MessageFormat2.format(template, Map("n" -> n), locale)
      assertTrue(
        at(0, "fr") == "one",
        at(0, "fr-BE") == "one",
        at(0, "FR-ca") == "one",
        at(1, "fr") == "one",
        at(0, "en") == "other",
        at(0, "nl-BE") == "other",
        at(0, "fra") == "other",
        at(0, "frxxxxxxxxx") == "other",
        at(1, "en") == "one",
        at(2, "fr") == "other",
      )
    },
  )

end PluralLanguageSpec
