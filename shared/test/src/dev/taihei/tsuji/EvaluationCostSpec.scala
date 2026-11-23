package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}
import java.util.concurrent.atomic.AtomicInteger

import zio.test.*

// What one evaluation reads and when: a date or time style, an option's value, a spent budget,
// and the memo that lets one evaluation write a value with the same options once.
object EvaluationCostSpec extends ZIOSpecDefault:

  private val day      = LocalDate.of(2026, 11, 10)
  private val dayTime  = LocalDateTime.of(2026, 11, 10, 14, 5)
  private val time     = LocalTime.of(14, 5)
  private val longText = "l" * 60000

  // A value that counts how often it is written as text: :string writes it with toString.
  final private class Counted(count: AtomicInteger):

    override def toString: String =
      count.incrementAndGet()
      "x"

  // Options that count every walk over them: a key made of the options, their hash or a filter
  // over them walks them all, a lookup of one option does not.
  final private class Walked(options: Map[String, String], walks: AtomicInteger)
    extends scala.collection.immutable.AbstractMap[String, String]:

    def get(key: String): Option[String] = options.get(key)

    def iterator: Iterator[(String, String)] =
      walks.incrementAndGet()
      options.iterator

    def removed(key: String): Map[String, String] = Walked(options - key, walks)

    def updated[V >: String](key: String, value: V): Map[String, V] = options.updated(key, value)

  def spec = suite("What an evaluation reads")(
    test("the platform formatters read a date or time style of at most six letters") {
      val options            = Map("dateStyle" -> longText, "timeStyle" -> longText)
      def medium(value: Any) = PlatformDateTimeFormatter.formatDateTime(value, Map.empty, "en")
      TimeBudget.within("20000 dates, times and both with 60K styles", 1000)(
        (1 to 20000).map(_ =>
          List(day, dayTime, time).map(PlatformDateTimeFormatter.formatDateTime(_, options, "en"))
        ).last
      ): out =>
        assertTrue(out == List(medium(day), medium(dayTime), medium(time)))
    },
    // How long the formatters take depends on the machine; how that time grows with the style
    // does not. Reading a style of 600K letters, even once per date, takes a hundred times as long.
    test("the platform formatters take as long for a style of 600K letters as for one of seven") {
      def formatted(style: String) =
        val options = Map("dateStyle" -> style, "timeStyle" -> style)
        (1 to 2000).map(_ =>
          List(day, dayTime, time).map(PlatformDateTimeFormatter.formatDateTime(_, options, "en"))
        ).last
      val growth                   =
        TimeBudget.growth("2000 dates, times and both, a style of 7 and of 600K letters", _ < 20)(
          formatted("mediumx")
        )(formatted("l" * 600000))
      assertTrue(growth.ratio < 20, growth.first == growth.second, growth.second == formatted("medium"))
    },
    test("an over-long option is no option: a 60K dateStyle formats as medium, cheaply") {
      val shapes = List(
        s".match {$$v :date dateStyle=$longText}\nwhen * {}" -> day,
        s"{$$v :date dateStyle=$longText}" -> day,
        s"{$$v :time timeStyle=$longText}" -> time,
      ).map((template, value) => MessageFormat2.parse(template).message -> value)
      TimeBudget.within("3 x 20000 evaluations of a 60K date or time style", 1000)(
        shapes.map: (message, value) =>
          (1 to 20000).map(_ => MessageFormat2.formatted(message, Map("v" -> value), "en", Budget.fresh())).last
      ): out =>
        assertTrue(
          out == List(
            Right(""),
            Right(MessageFormat2.format("{$v :date}", Map("v" -> day))),
            Right(MessageFormat2.format("{$v :time}", Map("v" -> time))),
          )
        )
    },
    // An evaluation pays for itself first, and each selector before it is evaluated: on a spent
    // budget nothing of the template is evaluated, and on a budget that pays for two selectors
    // the third is never evaluated.
    test("an evaluation pays for each step before it does it") {
      val message                                         = MessageFormat2
        .parse(".match {$v :string} {$v :string} {$v :string}\nwhen * * * {x}")
        .message
      def written(budget: Budget, times: Int): (Int, Int) =
        val count = new AtomicInteger
        val value = new Counted(count)
        val left  = (1 to times).count(_ =>
          MessageFormat2.formatted(message, Map("v" -> value), "en", budget).isLeft
        )
        (count.get, left)
      assertTrue(
        written(Budgets.withLeft(-1), 5000) == (0, 5000),
        // One step for the evaluation, one for each of two selectors, and nothing for a third.
        written(Budgets.withLeft(3), 1) == (2, 1),
        written(Budget.fresh(), 1) == (3, 0),
      )
    },
    // Looked up by every option, 20000 uses cost 4.4 s on Scala.js and 0.66 s on the JVM; looked
    // up by what the options come to, about 30 ms on both. The budget leaves room for a loaded
    // machine, so the test after this one catches the old key on any machine.
    test("two thousand options a function does not read cost nothing per use") {
      val many    = (1 to 2000).map(i => s"o$i=aaaaaaaaaaaaaa").mkString(" ")
      val message = MessageFormat2.parse(s".match {$$v :date $many}\nwhen * {}").message
      TimeBudget.within("20000 evaluations of a function with 2000 options", 1000)(
        (1 to 20000).map(_ => MessageFormat2.formatted(message, Map("v" -> day), "en", Budget.fresh())).last
      ): out =>
        assertTrue(out == Right(""))
    },
    test("a function's options are not walked over, whichever use of the function it is") {
      val walks   = new AtomicInteger
      val many    = Walked((1 to 2000).map(i => s"o$i" -> "aaaaaaaaaaaaaa").toMap, walks)
      val message = MessageFormat2.Message(
        Nil,
        Right(
          MessageFormat2.MatchStatement(
            List(MessageFormat2.FunctionAnnotation(MessageFormat2.Variable("v"), "date", many)),
            List(MessageFormat2.MatchCase(List("*"), MessageFormat2.Text(""))),
          )
        ),
      )
      val out     = (1 to 20000).map(_ => MessageFormat2.formatted(message, Map("v" -> day), "en", Budget.fresh()))
      assertTrue(out.forall(_ == Right("")), walks.get == 0)
    },
    // One evaluation writes a value with the same options once, and looks it up by what the
    // options come to: every option set a formatter reads must give, inside one message among
    // all the others, the text it gives alone.
    test("a value written among others reads as it does alone") {
      val number      = 12345.678
      val numbers     = List(
        "{$v}",
        "{$v :string}",
        "{$v :number}",
        "{$v :number useGrouping=never}",
        "{$v :number useGrouping=always}",
        "{$v :number useGrouping=NEVER}",
        "{$v :number style=currency currency=eur}",
        "{$v :number style=currency currency=EUR}",
        "{$v :number style=currency currency=usd}",
        "{$v :number style=percent}",
        "{$v :number minimumFractionDigits=2}",
        "{$v :number maximumFractionDigits=0}",
        "{$v :number minimumFractionDigits=5 maximumFractionDigits=1}",
        "{$v :integer}",
        "{$v :integer useGrouping=never}",
      )
      val dates       = List(
        "{$v}",
        "{$v :date}",
        "{$v :date dateStyle=LONG}",
        "{$v :date dateStyle=long}",
        "{$v :date dateStyle=Short}",
        "{$v :date dateStyle=longest}",
        "{$v :date timeStyle=short}",
        "{$v :time}",
        "{$v :time timeStyle=SHORT}",
        "{$v :time dateStyle=full}",
        "{$v :datetime}",
        "{$v :datetime dateStyle=full timeStyle=short}",
        "{$v :datetime dateStyle=SHORT timeStyle=Short}",
      )
      def differences(
        templates: List[String],
        value: Any,
        tag: String,
      ): List[String] =
        def alone(t: String)           = MessageFormat2.formatIn(t, Map("v" -> value), tag)
        def together(ts: List[String]) =
          MessageFormat2.formatIn(ts.mkString("\u0001"), Map("v" -> value), tag).split('\u0001').toList
        val expected                   = templates.map(alone)
        val forward                    = together(templates)
        val backward                   = together(templates.reverse).reverse
        templates.indices.toList.collect:
          case i if forward(i) != expected(i) || backward(i) != expected(i) =>
            s"$tag ${templates(i)}: alone ${expected(i)}, together ${forward(i)} / ${backward(i)}"
      val tags        = List("en", "nl", "fr", "de", "ja")
      val wrong       =
        tags.flatMap(tag => differences(numbers, number, tag) ++ differences(dates, dayTime, tag))
      assertTrue(
        MessageFormat2.formatIn("{$v :number useGrouping=never}", Map("v" -> number), "en") != "12,345.678",
        wrong.isEmpty,
      )
    },
    // A 4.x message brings its own templates, which are formatted without the checks a catalogue
    // template passes, so a variant may have fewer keys than the match has selectors.
    test("variants without keys under thousands of selectors, evaluated until the budget is spent") {
      val parsed = MessageFormat2.parse(".match " + "{1}" * 8000 + "\n" + "when {}\n" * 5000).message
      TimeBudget.within("variants without keys under 8000 selectors, one budget", 3000)(
        evaluationsUntilSpent(parsed, 10000)
      ): evaluations =>
        // Each evaluation pays for 8000 selectors and 5000 variants: about 300 fit in the budget.
        assertTrue(evaluations >= 250, evaluations <= 320)
    },
    // One step for the evaluation, one for the selector, ten for "[no match]", and 8000 for the
    // variants tried: without the step per variant, a budget would pay for 333333 evaluations.
    test("each variant tried is a step, also one without keys") {
      val parsed = MessageFormat2.parse(".match {1}\n" + "when {}\n" * 8000).message
      assertTrue(
        MessageFormat2.formatIn(".match {1}\nwhen {}\nwhen * {x}") == "x",
        evaluationsUntilSpent(parsed, 1000) == (Budget.PerMessage / (1 + 1 + 10 + 8000)).toInt,
      )
    },
    // A key is read whole to see whether it names a number, so it costs its length: a key of
    // 20000 digits leaves a budget for at most 200 evaluations, not the cap of 1000.
    test("a key tried costs its length") {
      val parsed = MessageFormat2.parse(".match {1 :plural}\nwhen =" + "9" * 20000 + " {x}\nwhen * {y}").message
      assertTrue(
        MessageFormat2.formatIn(".match {1 :plural}\nwhen =99 {x}\nwhen * {y}") == "y",
        evaluationsUntilSpent(parsed, 1000) <= (Budget.PerMessage / 20000).toInt,
      )
    },
    test("a variant with fewer keys than selectors is passed over") {
      val template = ".match {$a} {$b}\nwhen x {one key}\nwhen x y {both}\nwhen * * {other}"
      assertTrue(
        MessageFormat2.formatIn(template, Map("a" -> "x", "b" -> "y")) == "both",
        MessageFormat2.formatIn(template, Map("a" -> "x", "b" -> "z")) == "other",
      )
    },
    // The shape of TAIHEI-208: a plural selector tried against 20000 `one` variants under a locale
    // of a million letters stays within its bound.
    test("twenty thousand `one` variants with a locale of a million letters") {
      val parsed = MessageFormat2
        .parse(".match {$n :plural}\n" + "when one {one}\n" * 20000 + "when * {other}")
        .message
      val locale = "x" * 1000000
      TimeBudget.within("20000 one variants, a locale of 1M letters", 1000)(
        MessageFormat2.tryFormat(parsed, Map("n" -> 2), locale)
      ): out =>
        assertTrue(out.contains("other"))
    },
  )

  // How many times a message is evaluated over one budget before the budget is spent, as the
  // nested messages of one message are, up to `cap`.
  private def evaluationsUntilSpent(message: MessageFormat2.Message, cap: Int): Int =
    val budget = Budget.fresh()
    Iterator
      .continually(MessageFormat2.tryFormat(message, Map.empty, "en", budget))
      .take(cap)
      .takeWhile(_.isDefined)
      .size

end EvaluationCostSpec
