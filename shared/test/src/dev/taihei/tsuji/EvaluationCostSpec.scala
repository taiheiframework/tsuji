package dev.taihei.tsuji

import java.time.LocalDate

import zio.json.*
import zio.test.*

import dev.taihei.tsuji.Language.*

// A family whose Dutch templates come from a catalogue, as a translation file the application did
// not compile does, and a message that lists messages of it, as a message from the wire can.
object Costs extends MessageFamily("cost"):

  val leaf = message[(d: LocalDate, l: Language)]("{$d} {$l}")

  val many = message[(items: List[Message])]("{$items}")

// What one message may cost, its nested messages included, is bounded by its budget: work that
// writes nothing (a declaration, a selector, a date or a name written and never shown) is paid for
// as text is, and before it is done. Each test below would take tens of seconds without that.
object EvaluationCostSpec extends ZIOSpecDefault:

  private val day = LocalDate.of(2026, 10, 20)

  // About 60K characters each, under the 64K a template may have.
  private val templates: List[(String, String)] = List(
    "date declarations" ->
    ((1 to 1300).map(i => s".local $$x$i = {$$d :date}").mkString("\n") + "\nx"),
    "date selectors" ->
    (".match " + "{$d :date}" * 3700 + "\nwhen" + " *" * 3700 + " {x}"),
    "language selectors" ->
    (".match " + "{$l :string}" * 4000 + "\nwhen" + " *" * 4000 + " {x}"),
    "language declarations" ->
    ((1 to 2200).map(i => s".local $$y$i = {$$l :string}").mkString("\n") + "\nx"),
    "long names" -> {
      val names = (1 to 5).map(i => "n" * 6000 + i)
      names.map(n => s".local $$$n = {(a)}").mkString("\n") + "\n" + names.map(n => s"{$$$n}").mkString
    },
  )

  // The message as it arrives: written to JSON and read back, four thousand nested messages.
  private def wire(catalogues: Catalogues): Message =
    given Catalogues = catalogues
    Costs.many(List.fill(4000)(Costs.leaf(day, Language("de")))).toJson.fromJson[Message].toOption.get

  def spec = suite("The cost of one message")(
    suite("a message of four thousand nested messages over a template that writes nothing")(
      templates.map: (shape, template) =>
        test(shape) {
          val catalogues = Catalogues(Seq(Costs))
          val problems   = catalogues.add(Catalogue(Dutch, Map(Costs.leaf.key -> template)))
          val message    = wire(catalogues)
          TimeBudget.within(s"4000 nested messages, $shape", 5000)(
            message.localize(Dutch)(
              using catalogues
            )
          ): out =>
            // Past the budget the message falls back to its own template.
            assertTrue(template.length < MaxTemplate, problems.isEmpty, out == "{$items}")
        }
    ),
    test("a few nested messages over the same templates are formatted") {
      val catalogues = Catalogues(Seq(Costs))
      catalogues.add(Catalogue(Dutch, Map(Costs.leaf.key -> templates.head._2)))
      val out        = Costs.many(List.fill(3)(Costs.leaf(day, Language("de")))).localize(Dutch)(
        using catalogues
      )
      assertTrue(out == "x, x, x")
    },
    test("a date style of sixty thousand letters is written as medium, twenty thousand times") {
      // Parsed once, as a catalogue template is, and formatted each time it is shown.
      val parsed = MessageFormat2.parse(s"{$$d :date dateStyle=long${"g" * 60000}}").message
      TimeBudget.within("20000 dates with a style of 60004 letters", 3000)(
        (1 to 20000).map(_ => MessageFormat2.tryFormat(parsed, Map("d" -> day), "nl").getOrElse(""))
      ): out =>
        assertTrue(out.forall(_ == MessageFormat2.format("{$d :date}", Map("d" -> day), "nl")))
    },
    test("the platform is given only the styles, each one of the four, in lower case") {
      val options = Map(
        "dateStyle" -> ("long" + "g" * 60000),
        "timeStyle" -> "SHORT",
        "calendar" -> "x",
      )
      assertTrue(
        MessageFormat2.styles(options) == Map("timeStyle" -> "short"),
        MessageFormat2.styles(Map("dateStyle" -> "Full", "timeStyle" -> "medium")) ==
        Map("dateStyle" -> "full", "timeStyle" -> "medium"),
        MessageFormat2.styles(Map("dateStyle" -> "longer")).isEmpty,
      )
    },
    test("a style in any case is still read") {
      assertTrue(
        MessageFormat2.format("{$d :date dateStyle=LONG}", Map("d" -> day), "en") ==
        MessageFormat2.format("{$d :date dateStyle=long}", Map("d" -> day), "en"),
        MessageFormat2.format("{$d :date dateStyle=long}", Map("d" -> day), "en") !=
        MessageFormat2.format("{$d :date}", Map("d" -> day), "en"),
      )
    },
    test("twenty thousand `one` variants with a locale of a million letters") {
      val template = ".match {$n :plural}\n" + "when one {one}\n" * 20000 + "when * {other}"
      val locale   = "x" * 1000000
      TimeBudget.within("20000 one variants, a locale of 1M letters", 1000)(
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

  private val MaxTemplate = MessageFormat2.MaxTemplateLength

end EvaluationCostSpec
