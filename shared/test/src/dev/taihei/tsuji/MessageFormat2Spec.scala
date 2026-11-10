package dev.taihei.tsuji

import zio.test.*

object MessageFormat2Spec extends ZIOSpecDefault:

  def spec = suite("MessageFormat2Spec")(
    suite("Simple message formatting")(
      test("formats plain text without variables") {
        val result = MessageFormat2.format("Hello World")
        assertTrue(result == "Hello World")
      },
      test("formats text with single variable") {
        val result = MessageFormat2.format("Hello {$name}", Map("name" -> "Alice"))
        assertTrue(result == "Hello Alice")
      },
      test("formats text with multiple variables") {
        val result = MessageFormat2.format(
          "{$greeting} {$name}!",
          Map("greeting" -> "Hello", "name" -> "Bob"),
        )
        assertTrue(result == "Hello Bob!")
      },
      test("handles missing variables gracefully") {
        val result = MessageFormat2.format("Hello {$name}")
        assertTrue(result == "Hello $name")
      },
      test("formats text with numeric variables") {
        val result = MessageFormat2.format(
          "You have {$count} messages",
          Map("count" -> 5),
        )
        assertTrue(result == "You have 5 messages")
      },
    ),
    suite("Input declarations")(
      test("processes .input declarations") {
        val template = """.input {$name}
                         |Hello {$name}""".stripMargin
        val result   = MessageFormat2.format(template, Map("name" -> "World"))
        assertTrue(result.trim == "Hello World")
      },
      test("processes .input with function annotation") {
        val template = """.input {$count :number}
                         |Count: {$count}""".stripMargin
        val result   = MessageFormat2.format(template, Map("count" -> 42))
        assertTrue(result.trim.contains("42"))
      },
      test("processes multiple .input declarations") {
        val template = """.input {$firstName}
                         |.input {$lastName}
                         |{$firstName} {$lastName}""".stripMargin
        val result   = MessageFormat2.format(
          template,
          Map("firstName" -> "John", "lastName" -> "Doe"),
        )
        assertTrue(result.trim == "John Doe")
      },
    ),
    suite("Local declarations (.let)")(
      test("processes .let declarations") {
        val template = """.let {$greeting = (Hello)}
                         |{$greeting} World""".stripMargin
        val result   = MessageFormat2.format(template)
        assertTrue(result.trim == "Hello World")
      },
      test(".let can reference input variables") {
        val template = """.input {$name}
                         |.let {$message = (Hello)}
                         |{$message} {$name}""".stripMargin
        val result   = MessageFormat2.format(template, Map("name" -> "Alice"))
        assertTrue(result.trim == "Hello Alice")
      },
    ),
    suite("Function annotations")(
      test(":number function formats numbers") {
        val template = "Price: {$price :number}"
        val result   = MessageFormat2.format(template, Map("price" -> 19.99))
        assertTrue(result == "Price: 19.99")
      },
      test(":number with style option") {
        val template = "Price: {$price :number style=currency currency=USD}"
        val result   = MessageFormat2.format(template, Map("price" -> 19.99))
        assertTrue(result == "Price: $19.99")
      },
      test(":plural function returns plural selector") {
        val template = "{$count :plural}"
        val result   = MessageFormat2.format(template, Map("count" -> 1))
        assertTrue(result == "1")
      },
    ),
    suite("Match statements")(
      test("simple match with exact keys") {
        val template = """.match {$count}
                         |when 1 {You have one item}
                         |when 2 {You have two items}
                         |when * {You have many items}""".stripMargin
        val result1  = MessageFormat2.format(template, Map("count" -> 1))
        val result2  = MessageFormat2.format(template, Map("count" -> 2))
        val result3  = MessageFormat2.format(template, Map("count" -> 5))

        assertTrue(
          result1.trim == "You have one item",
          result2.trim == "You have two items",
          result3.trim == "You have many items",
        )
      },
      test("match with plural selector") {
        val template = """.match {$count :plural}
                         |when 1 {one item}
                         |when other {many items}""".stripMargin
        val result1  = MessageFormat2.format(template, Map("count" -> 1))
        val result2  = MessageFormat2.format(template, Map("count" -> 5))

        assertTrue(
          result1.trim == "one item",
          result2.trim == "many items",
        )
      },
      test("match with variables in patterns") {
        val template = """.match {$count}
                         |when 1 {You have {$count} item}
                         |when * {You have {$count} items}""".stripMargin
        val result1  = MessageFormat2.format(template, Map("count" -> 1))
        val result2  = MessageFormat2.format(template, Map("count" -> 5))

        assertTrue(
          result1.trim == "You have 1 item",
          result2.trim == "You have 5 items",
        )
      },
      test("match with =N exact match syntax") {
        val template = """.match {$count :plural}
                         |when =0 {no items}
                         |when =1 {one item}
                         |when other {many items}""".stripMargin
        val result0  = MessageFormat2.format(template, Map("count" -> 0))
        val result1  = MessageFormat2.format(template, Map("count" -> 1))
        val result5  = MessageFormat2.format(template, Map("count" -> 5))

        assertTrue(
          result0.trim == "no items",
          result1.trim == "one item",
          result5.trim == "many items",
        )
      },
      test("match with 'one' and 'other' plural categories") {
        val template = """.match {$count :plural}
                         |when one {singular}
                         |when other {plural}""".stripMargin
        val result1  = MessageFormat2.format(template, Map("count" -> 1))
        val result2  = MessageFormat2.format(template, Map("count" -> 2))

        assertTrue(
          result1.trim == "singular",
          result2.trim == "plural",
        )
      },
      test("match returns [no match] for unmatched cases") {
        val template = """.match {$count}
                         |when 1 {one}
                         |when 2 {two}""".stripMargin
        val result   = MessageFormat2.format(template, Map("count" -> 3))
        assertTrue(result.trim == "[no match]")
      },
    ),
    suite("Multi-selector match statements")(
      test("match with multiple selectors") {
        val template = """.match {$gender} {$count :plural}
                         |when male 1 {He has one item}
                         |when male other {He has many items}
                         |when female 1 {She has one item}
                         |when female other {She has many items}
                         |when * * {They have items}""".stripMargin

        val result1 = MessageFormat2.format(template, Map("gender" -> "male", "count" -> 1))
        val result2 = MessageFormat2.format(template, Map("gender" -> "female", "count" -> 1))
        val result3 = MessageFormat2.format(template, Map("gender" -> "male", "count" -> 5))
        val result4 = MessageFormat2.format(template, Map("gender" -> "other", "count" -> 1))

        assertTrue(
          result1.trim == "He has one item",
          result2.trim == "She has one item",
          result3.trim == "He has many items",
          result4.trim == "They have items",
        )
      }
    ),
    suite("Complex message patterns")(
      test("message with declarations and match statement") {
        val template = """.input {$count :number}
                         |.match {$count :plural}
                         |when 0 {No items}
                         |when 1 {One item}
                         |when other {{$count} items}""".stripMargin

        val result0 = MessageFormat2.format(template, Map("count" -> 0))
        val result1 = MessageFormat2.format(template, Map("count" -> 1))
        val result5 = MessageFormat2.format(template, Map("count" -> 5))

        assertTrue(
          result0.trim == "No items",
          result1.trim == "One item",
          result5.trim.contains("5 items"),
        )
      },
      test("message with let and match") {
        val template = """.let {$max = (10)}
                         |.match {$count}
                         |when 0 {Empty}
                         |when * {Has items}""".stripMargin

        val result = MessageFormat2.format(template, Map("count" -> 5))
        assertTrue(result.trim == "Has items")
      },
    ),
    suite("Locale support")(
      test("format accepts locale parameter") {
        val result = MessageFormat2.format("Hello {$name}", Map("name" -> "World"), "en")
        assertTrue(result == "Hello World")
      },
      test("different locales can be specified") {
        val template = "Price: {$price :number}"
        val resultEn = MessageFormat2.format(template, Map("price" -> 19.99), "en")
        val resultNl = MessageFormat2.format(template, Map("price" -> 19.99), "nl")

        // English writes the decimals after a point, Dutch after a comma.
        assertTrue(resultEn == "Price: 19.99", resultNl == "Price: 19,99")
      },
      test("French plural rules: 0 and 1 are 'one'") {
        val template = """.match {$count :plural}
                         |when one {un élément}
                         |when other {plusieurs éléments}""".stripMargin
        val result0  = MessageFormat2.format(template, Map("count" -> 0), "fr")
        val result1  = MessageFormat2.format(template, Map("count" -> 1), "fr")
        val result2  = MessageFormat2.format(template, Map("count" -> 2), "fr")

        assertTrue(
          result0.trim == "un élément",        // French: 0 is "one"
          result1.trim == "un élément",        // French: 1 is "one"
          result2.trim == "plusieurs éléments", // French: 2+ is "other"
        )
      },
      test("English/Dutch plural rules: only 1 is 'one'") {
        val template  = """.match {$count :plural}
                          |when one {singular}
                          |when other {plural}""".stripMargin
        val resultEn0 = MessageFormat2.format(template, Map("count" -> 0), "en")
        val resultEn1 = MessageFormat2.format(template, Map("count" -> 1), "en")
        val resultNl0 = MessageFormat2.format(template, Map("count" -> 0), "nl")
        val resultNl1 = MessageFormat2.format(template, Map("count" -> 1), "nl")

        assertTrue(
          resultEn0.trim == "plural",   // English: 0 is "other"
          resultEn1.trim == "singular", // English: 1 is "one"
          resultNl0.trim == "plural",   // Dutch: 0 is "other"
          resultNl1.trim == "singular", // Dutch: 1 is "one"
        )
      },
    ),
    suite("DateTime formatting")(
      test("formats LocalDate with locale") {
        import java.time.LocalDate
        val date     = LocalDate.of(2024, 1, 15)
        val template = "{$date :datetime}"
        val resultEn = MessageFormat2.format(template, Map("date" -> date), "en")
        val resultFr = MessageFormat2.format(template, Map("date" -> date), "fr")

        assertTrue(resultEn == "Jan 15, 2024", resultFr == "15 janv. 2024")
      },
      test("formats LocalDate with different dateStyle options") {
        import java.time.LocalDate
        val date          = LocalDate.of(2024, 1, 15)
        val templateShort = "{$date :datetime dateStyle=short}"
        val templateLong  = "{$date :datetime dateStyle=long}"

        val resultShort = MessageFormat2.format(templateShort, Map("date" -> date), "en")
        val resultLong  = MessageFormat2.format(templateLong, Map("date" -> date), "en")

        assertTrue(resultShort == "1/15/24", resultLong == "January 15, 2024")
      },
      test("formats LocalDateTime with locale") {
        import java.time.LocalDateTime
        val datetime = LocalDateTime.of(2024, 1, 15, 14, 30)
        val template = "{$datetime :datetime}"
        val resultEn = MessageFormat2.format(template, Map("datetime" -> datetime), "en")
        val resultNl = MessageFormat2.format(template, Map("datetime" -> datetime), "nl")

        // The space before PM is a plain one or U+202F, and the Dutch month and the separator
        // between date and time differ, by the version of the platform's locale data.
        assertTrue(
          resultEn.replace('\u202f', ' ') == "Jan 15, 2024, 2:30:00 PM",
          resultNl.startsWith("15 jan"),
          resultNl.contains("2024"),
          resultNl.endsWith("14:30:00"),
        )
      },
      test("formats LocalTime with locale") {
        import java.time.LocalTime
        val time     = LocalTime.of(14, 30, 0)
        val template = "{$time :datetime}"
        val resultEn = MessageFormat2.format(template, Map("time" -> time), "en")
        val resultFr = MessageFormat2.format(template, Map("time" -> time), "fr")

        assertTrue(resultEn.replace('\u202f', ' ') == "2:30:00 PM", resultFr == "14:30:00")
      },
    ),
    suite("Edge cases")(
      test("empty template returns empty string") {
        val result = MessageFormat2.format("")
        assertTrue(result == "")
      },
      test("template with only whitespace") {
        val result = MessageFormat2.format("   ")
        assertTrue(result == "   ")
      },
      test("template with braces but no variables") {
        val result = MessageFormat2.format("Test { } text")
        assertTrue(result.contains("Test"))
      },
      test("nested braces in literal") {
        val template = ".let {$msg = (Hello {World})}\n{$msg}"
        val result   = MessageFormat2.format(template)
        // The literal should preserve nested content
        assertTrue(result.contains("Hello"))
      },
      test("variable names with underscores") {
        val result = MessageFormat2.format(
          "Hello {$first_name} {$last_name}",
          Map("first_name" -> "John", "last_name" -> "Doe"),
        )
        assertTrue(result == "Hello John Doe")
      },
    ),
    suite("Parser robustness")(
      test("handles templates with newlines") {
        val template = """Hello
                         |{$name}
                         |Welcome!""".stripMargin
        val result   = MessageFormat2.format(template, Map("name" -> "Alice"))
        assertTrue(
          result.contains("Hello"),
          result.contains("Alice"),
          result.contains("Welcome"),
        )
      },
      test("handles mixed text and variables") {
        val template = "Start {$var1} middle {$var2} end"
        val result   = MessageFormat2.format(
          template,
          Map("var1" -> "A", "var2" -> "B"),
        )
        assertTrue(result == "Start A middle B end")
      },
    ),
    suite("Keys and options that are not what they claim")(
      test("a key `=abc` matches no count instead of throwing") {
        val template = """.match {$count :plural}
                         |when =abc {named}
                         |when other {other}""".stripMargin
        assertTrue(MessageFormat2.format(template, Map("count" -> 3)).trim == "other")
      },
      test("a numeric key beyond Int range matches no count instead of throwing") {
        val template = """.match {$count :plural}
                         |when 99999999999 {huge}
                         |when =99999999999 {huge too}
                         |when 3 {three}
                         |when other {other}""".stripMargin
        assertTrue(
          MessageFormat2.format(template, Map("count" -> 3)).trim == "three",
          MessageFormat2.format(template, Map("count" -> 4)).trim == "other",
        )
      },
      test("an unknown dateStyle or timeStyle falls back to medium on both platforms") {
        import java.time.{LocalDate, LocalDateTime, LocalTime}
        val date                 = LocalDate.of(2024, 1, 15)
        val dt                   = LocalDateTime.of(2024, 1, 15, 14, 30)
        val time                 = LocalTime.of(14, 30)
        def f(t: String, v: Any) = MessageFormat2.format(t, Map("v" -> v), "en")
        val medium               = f("{$v :datetime dateStyle=medium}", date)
        assertTrue(
          medium.nonEmpty,
          f("{$v :datetime dateStyle=bogus}", date) == medium,
          f("{$v :datetime dateStyle=bogus timeStyle=bogus}", dt) == f("{$v :datetime}", dt),
          f("{$v :datetime timeStyle=bogus}", time) == f("{$v :datetime timeStyle=medium}", time),
        )
      },
      test("a long or full timeStyle on a value without a zone gives a value on both platforms") {
        import java.time.{LocalDateTime, LocalTime}
        val dt                   = LocalDateTime.of(2024, 1, 15, 14, 30)
        val time                 = LocalTime.of(14, 30)
        def f(t: String, v: Any) = MessageFormat2.format(t, Map("v" -> v), "en")
        val results              =
          for
            style  <- List("long", "full")
            (t, v) <- List(s"{$$v :datetime timeStyle=$style}" -> time, s"{$$v :datetime timeStyle=$style}" -> dt)
          yield f(t, v)
        assertTrue(results.size == 4, results.forall(r => r.nonEmpty && r.contains("30")))
      },
      test("a style is read in any case, as on the JVM") {
        import java.time.LocalDate
        val date         = LocalDate.of(2024, 1, 15)
        def f(t: String) = MessageFormat2.format(t, Map("v" -> date), "en")
        assertTrue(f("{$v :datetime dateStyle=LONG}") == f("{$v :datetime dateStyle=long}"))
      },
    ),
    suite("Counts that are not whole")(
      // The count was once cut to an Int: 1.5 read "One item", in every language.
      test("a fraction is neither one nor an exact number") {
        val template                          = """.match {$count :plural}
                                                  |when =0 {none}
                                                  |when 1 {exactly one}
                                                  |when one {one}
                                                  |when other {other}
                                                  |when * {default}""".stripMargin
        def f(count: Any, tag: String = "en") =
          MessageFormat2.format(template, Map("count" -> count), tag).trim
        assertTrue(
          f(1.5) == "other",
          f(0.5) == "other",
          // French counts everything below 2 as one, but 0.5 is still not the number 1.
          f(0.5, "fr") == "one",
          f(1.5, "fr") == "one",
          f(1.0) == "exactly one",
          f(1) == "exactly one",
          f(0) == "none",
          f(1e12) == "other",
          f(Double.NaN) == "other",
        )
      },
      test("the language of one is read without the default locale") {
        val template = ".match {$count :plural}\nwhen one {one}\nwhen * {other}"
        assertTrue(
          MessageFormat2.format(template, Map("count" -> 0), "FR-be").trim == "one",
          MessageFormat2.format(template, Map("count" -> 0), "en-GB").trim == "other",
        )
      },
    ),
    suite("Parsing in place")(
      test(".match after leading whitespace is still a match") {
        val template = "  \t.match {$count}\nwhen 1 {one}\nwhen * {many}"
        assertTrue(MessageFormat2.format(template, Map("count" -> 1)).trim == "one")
      },
      test("a match with thousands of cases selects the last one") {
        val n        = 5000
        val cases    = (1 to n).map(i => s"when $i {case $i}").mkString("\n")
        val template = s".match {$$count}\n$cases\nwhen * {none}"
        assertTrue(MessageFormat2.format(template, Map("count" -> n)).trim == s"case $n")
      },
    ),
  )

end MessageFormat2Spec
