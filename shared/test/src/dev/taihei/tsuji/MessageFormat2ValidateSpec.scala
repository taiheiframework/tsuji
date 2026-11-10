package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import zio.test.*

object MessageFormat2ValidateSpec extends ZIOSpecDefault:

  private def errors(t: String): List[String] = MessageFormat2.validate(t).map(_.message)

  private def checked(t: String, args: (String, ArgKind)*) = MessageFormat2.check(t, args.toMap)

  private val shipped =
    ".match {$count :plural} when one {One item shipped on {$on :datetime dateStyle=long}} " +
    "when * {{$count} items shipped on {$on :datetime dateStyle=long}}"

  def spec = suite("MessageFormat2 validate, check and the 5.0 fixes")(
    suite("validate accepts")(
      test("the templates of the documentation") {
        val valid = List(
          "Hello World",
          "",
          "Hello {$name}",
          "{$n :number minimumFractionDigits=2 maximumFractionDigits=4 useGrouping=never}",
          "{$n :integer useGrouping=always}",
          "Price: {$price :number style=currency currency=EUR}",
          "Price: {$price :number style=currency currency=eur}",
          "{$d :datetime dateStyle=long}",
          ".input {$count :number}\nCount: {$count}",
          ".let {$greeting = (Hello)}\n{$greeting} World",
          ".local {$greeting = (Hello)}\n{$greeting} World",
          ".local $n = {$count :number}\n{$n}",
          ".match {$count :plural}\nwhen one {one}\nwhen * {{$count} items}",
          ".match {$g} {$c :plural}\nwhen male 1 {a}\nwhen * * {b}",
          shipped,
        )
        assertTrue(valid.forall(t => errors(t).isEmpty))
      }
    ),
    suite("validate refuses, with a position")(
      test("a currency that is not a three-letter code") {
        def refused(code: String) =
          MessageFormat2.validate(s"Price: {$$price :number style=currency currency=$code}")
        assertTrue(
          refused("euro").map(_.message) ==
          List("currency= takes a three-letter currency code such as EUR"),
          refused("euro").head.position == 15,
          refused("E1").size == 1,
          refused("EUR1").size == 1,
        )
      },
      // Up to 6.0.0 this was US dollars, whatever the application meant.
      test("style=currency without a currency") {
        assertTrue(
          MessageFormat2.validate("Price: {$price :number style=currency}").map(_.message) ==
          List("style=currency needs currency=, a three-letter currency code such as EUR")
        )
      },
      test("number options that are no number of decimals and no grouping") {
        def message(options: String) = MessageFormat2.validate(s"{$$n :number $options}").map(_.message)
        assertTrue(
          message("minimumFractionDigits=two") ==
          List("minimumFractionDigits= takes a whole number from 0 to 20"),
          message("maximumFractionDigits=21") ==
          List("maximumFractionDigits= takes a whole number from 0 to 20"),
          message("maximumFractionDigits=-1").nonEmpty,
          message("useGrouping=sometimes") == List("useGrouping= takes auto, always or never"),
          MessageFormat2.validate("{$n :integer useGrouping=no}").size == 1,
        )
      },
      test("an unclosed placeholder") {
        val e = MessageFormat2.validate("Hello {$name")
        assertTrue(e.size == 1, e.head.position == 6, e.head.message.contains("not closed"))
      },
      test("an unknown function") {
        assertTrue(errors("{$x :bogus}") == List("unknown function :bogus"))
      },
      test("a .match without a * variant") {
        assertTrue(errors(".match {$c :plural}\nwhen one {a}\nwhen other {b}").exists(_.contains("default variant")))
      },
      test("a variant with the wrong number of keys") {
        assertTrue(errors(".match {$a} {$b}\nwhen x {a}\nwhen * * {b}").exists(_.contains("needs 2 keys")))
      },
      test("text after the last variant") {
        assertTrue(errors(".match {$a}\nwhen * {a} trailing").exists(_.contains("after the last variant")))
      },
      test("a bare name instead of a variable") {
        assertTrue(errors("Hello {name}").exists(_.contains("{$name}")))
      },
      test("an unclosed literal and an unclosed quoted option") {
        assertTrue(
          errors(".let {$a = (open}\n{$a}").exists(_.contains("literal")),
          errors("{$d :datetime dateStyle=\"long}").exists(_.contains("quoted")),
        )
      },
      test("a variable without a name, a function without a name, an option without a value") {
        assertTrue(
          errors("{$}").exists(_.contains("needs a name")),
          errors("{$x :}").exists(_.contains("function needs a name")),
          errors("{$x :number style}").exists(_.contains("needs = and a value")),
        )
      },
      test("a template longer than the limit, without parsing it") {
        val e = MessageFormat2.validate("a" * (MessageFormat2.MaxTemplateLength + 1))
        assertTrue(e.size == 1, e.head.message.contains("longer"))
      },
    ),
    suite("check against the arguments")(
      test("a valid template with every argument used") {
        assertTrue(checked(shipped, "count" -> ArgKind.Number, "on" -> ArgKind.Date) == (Nil, Nil))
      },
      test("a variable that is not an argument") {
        val (e, _) = checked("Hello {$nmae}", "name" -> ArgKind.Text)
        assertTrue(e.map(_.message) == List("$nmae is not an argument of this message"))
      },
      test("an unused argument is named, not an error") {
        assertTrue(checked("Hello", "name" -> ArgKind.Text) == (Nil, List("name")))
      },
      test(":number on a date, :datetime on a number, :plural on text, :time on a date") {
        def fails(t: String, k: ArgKind) = checked(t, "x" -> k)._1.exists(_.message.contains("does not apply"))
        assertTrue(
          fails("{$x :number}", ArgKind.Date),
          fails("{$x :integer}", ArgKind.Text),
          fails("{$x :datetime}", ArgKind.Number),
          fails(".match {$x :plural}\nwhen * {a}", ArgKind.Text),
          fails("{$x :time}", ArgKind.Date),
          fails("{$x :date}", ArgKind.Time),
          fails(".input {$x :number}\n{$x}", ArgKind.Date),
          !fails("{$x :date}", ArgKind.DateTime),
          !fails("{$x :time}", ArgKind.DateTime),
          !fails("{$x :number}", ArgKind.Unknown),
        )
      },
      test(".input of something that is not an argument, a local declared twice") {
        assertTrue(
          checked(".input {$y}\n{$y}", "x" -> ArgKind.Text)._1.exists(_.message.contains(".input names $y")),
          checked(".local {$x = (a)}\n{$x}", "x" -> ArgKind.Text)._1.exists(_.message.contains("declared twice")),
        )
      },
      test("a local is in scope after its declaration, with the kind of its expression") {
        val t = ".local $n = {$count :number}\n.match {$n :plural}\nwhen * {{$n}}"
        assertTrue(checked(t, "count" -> ArgKind.Number) == (Nil, Nil))
      },
    ),
    suite("formatting fixed in 5.0")(
      test("a function inside a .match variant formats, on one line") {
        val date = LocalDate.of(2026, 10, 23)
        val one  = MessageFormat2.format(shipped, Map("count" -> 1, "on" -> date), Language.English)
        val many = MessageFormat2.format(shipped, Map("count" -> 3, "on" -> date), Language.English)
        assertTrue(
          one.startsWith("One item shipped on "),
          one.contains("2026"),
          !one.contains(":datetime"),
          many.startsWith("3 items shipped on "),
          many.contains("2026"),
        )
      },
      test(".local declares like .let, also in the form .local $x = {...}") {
        assertTrue(
          MessageFormat2.format(".local {$g = (Hello)}\n{$g} World") == "Hello World",
          MessageFormat2.format(".local $n = {$c :integer}\n{$n}", Map("c" -> 2.6)) == "3",
        )
      },
      test(":integer rounds, :date and :time take their part of a date and time") {
        val dt                   = LocalDateTime.of(2024, 1, 15, 14, 30)
        def f(t: String, v: Any) = MessageFormat2.format(t, Map("v" -> v), Language.English)
        assertTrue(
          f("{$v :integer}", 41.5) == "42",
          f("{$v :integer}", "n/a") == "n/a",
          f("{$v :date}", dt) == f("{$v :datetime}", dt.toLocalDate),
          f("{$v :time}", dt) == f("{$v :datetime}", dt.toLocalTime),
          f("{$v :date dateStyle=long}", LocalDate.of(2024, 1, 15)).contains("2024"),
          f("{$v :time}", LocalTime.of(9, 5)).contains("05"),
        )
      },
      test("a language shows as its name in the reader's language") {
        def f(l: Language, locale: String) = MessageFormat2.formatIn("{$l}", Map("l" -> l), locale)
        assertTrue(
          f(Language.Dutch, "en") == "Dutch",
          f(Language.Dutch, "fr") == "Néerlandais",
          f(Language.English, "nl-BE") == "Engels",
          f(Language("de"), "en") == "German",
        )
      },
      test("a .match on a language compares its tag") {
        val t = ".match {$l}\nwhen nl {Dag}\nwhen * {Hello}"
        assertTrue(
          MessageFormat2.format(t, Map("l" -> Language.Dutch)) == "Dag",
          MessageFormat2.format(t, Map("l" -> Language.French)) == "Hello",
        )
      },
    ),
    suite("input the application did not write")(
      test("deep nesting of parentheses, braces and variants gives errors, not a crash") {
        val n         = 100000
        val templates = List(
          "{(" * n,
          "{" * n,
          "}" * n,
          ".match " + "{$a}" * 1000 + "\n" + "when " + "* " * 1000 + "{x}",
          ".match {$a}\n" + "when * {" * n,
          ".local {$a = " * 10000,
          "{$a :number " + "k=v " * 10000,
          ".input" * n,
          "\u0000￿{$\ud800}",
        )
        val results   = templates.map(t => (MessageFormat2.validate(t), MessageFormat2.format(t, Map("a" -> 1))))
        assertTrue(results.size == templates.size, results.forall((e, _) => e.size <= 100))
      },
      test("random templates never throw") {
        check(Gen.stringBounded(0, 200)(Gen.elements(
          '{', '}', '$', ':', '(', ')', '=', '"', '*', '.', '\n', ' ', 'a', '1',
        ))) {
          t =>
            val e = MessageFormat2.validate(t)
            val c = MessageFormat2.check(t, Map("a" -> ArgKind.Number))
            val f = MessageFormat2.format(t, Map("a" -> 1))
            assertTrue(e.size <= 100, c._1.size <= 100, f != null)
        }
      },
    ),
  )

end MessageFormat2ValidateSpec
