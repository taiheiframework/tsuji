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
    test("numbers and dates with a locale of a million letters") {
      val locale    = "x" * 1000000
      val templates = List(
        "number placeholders" -> "{$n :number}" * 5000,
        "number declarations" -> ((1 to 3000).map(_ => s".input {$$n :number}").mkString("\n") + "\nx"),
        "date placeholders" -> "{$d :datetime}" * 4000,
        "date declarations" -> ((1 to 2000).map(i => s".let {$$d$i = $$d :datetime}").mkString("\n") + "\nx"),
      )
      val args      = Map[String, Any]("n" -> 7, "d" -> java.time.LocalDate.of(2026, 10, 20))
      TimeBudget.within("numbers and dates, a locale of 1M letters", 2000)(
        templates.map((_, t) => MessageFormat2.format(t, args, locale))
      ): out =>
        assertTrue(out.forall(_.nonEmpty), MessageFormat2.MaxLocaleLength == 255)
    },
    test("a locale is read up to 255 characters") {
      val template = ".match {$n :plural}\nwhen one {one}\nwhen * {other}"
      assertTrue(
        MessageFormat2.format(template, Map("n" -> 0), "fr" + "-x" * 200) == "one",
        MessageFormat2.format("{$n :number}", Map("n" -> 1234), "en" + "-x" * 200) ==
        MessageFormat2.format("{$n :number}", Map("n" -> 1234), "en"),
      )
    },
    test("a plural value is read as the value it holds, and never wrapped twice") {
      val chain =
        ".let {$p0 = $n :plural}\n" + (1 to 1000).map(i => s".let {$$p$i = $$p${i - 1} :plural}")
          .mkString("\n") + "\n{$p1000 :number} {$p1000}"
      assertTrue(
        MessageFormat2.format(chain, Map("n" -> 7)) == "7 7",
        MessageFormat2.format(".let {$p = $n :plural}\n{$p :number}", Map("n" -> 1234)) ==
        MessageFormat2.format("{$n :number}", Map("n" -> 1234)),
      )
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
