package dev.taihei.tsuji

import zio.test.*

// An argument can be text from the wire, and `:number` and `:plural` read it as a number. That
// read must take time linear in the text on both platforms, and happen once per selector.
object Readings extends MessageFamily("readings"):
  val all = message[(values: List[Double])]("{$values}")

object NumberArgumentSpec extends ZIOSpecDefault:

  // The Double whose exact value has the most digits, 767 of them: reading it as its shortest
  // digits once rounded all 767 seventeen times, on every use.
  private val subnormal = 2.225073858507201e-308

  private val longDigits = "1" * 65536 + "x"

  private val manyVariants =
    ".match {$n :plural}\n" + (1 to 2000).map(i => s"when =$i {v$i}").mkString("\n") + "\nwhen * {none}"

  def spec = suite("Number arguments")(
    test("long text that is no number shows as text under :number") {
      TimeBudget.within(":number of 65536 digits and a letter", 1000)(
        MessageFormat2.format("{$n :number}", Map("n" -> longDigits))
      ): out =>
        assertTrue(out == longDigits)
    },
    test("long text that is no number selects * under :plural with many variants") {
      TimeBudget.within(":plural of 65536 digits and a letter over 2000 variants", 1000)(
        MessageFormat2.format(manyVariants, Map("n" -> longDigits))
      ): out =>
        assertTrue(out == "none")
    },
    // A value can come from the wire, as text or as a BigDecimal of any exponent: what would be
    // written out is counted before it is.
    test("a number is written out only when it is of ordinary size") {
      def shown(value: Any, template: String = "{$n :number}") =
        MessageFormat2.format(template, Map("n" -> value))
      val longest                                              = "9" * 300
      val tooLong                                              = "9" * 301
      val huge                                                 = BigDecimal("1E+1000000000")
      val tiny                                                 = BigDecimal("1E-1000000000")
      TimeBudget.within("numbers at and past the limits", 2000)(
        List(
          shown(longest),
          shown(tooLong),
          shown("1" * (Operands.MaxNumberText + 1)),
          shown(huge),
          shown(tiny),
          shown(huge, "{$n :integer}"),
          shown(huge, "{$n}"),
          shown(huge, "{$n :string}"),
          shown(BigInt(2).pow(990)),
          shown(BigInt(2).pow(1000)),
          shown(huge, ".match {$n}\nwhen 1 {one}\nwhen * {other}"),
        )
      ): out =>
        assertTrue(
          out(0) == longest.grouped(3).mkString(","),
          out(1) == tooLong,
          out(2) == "1" * (Operands.MaxNumberText + 1),
          out(3) == huge.toString,
          out(4) == tiny.toString,
          out(5) == huge.toString,
          out(6) == huge.toString,
          out(7) == huge.toString,
          // 2^990 has 299 digits, 2^1000 has 302.
          out(8).count(_ == ',') == 99,
          out(9) == BigInt(2).pow(1000).toString,
          out(10) == "other",
        )
    },
    test("the bounds on decimals and on a whole number's bits, at the bound and one past it") {
      def shown(value: Any, template: String = "{$n :number maximumFractionDigits=20}") =
        MessageFormat2.format(template, Map("n" -> value))
      val longest = "0." + "0" * (Operands.MaxNumberText - 3) + "1"
      val scaled  = BigDecimal(1, Operands.MaxNumberText)
      val past    = BigDecimal(1, Operands.MaxNumberText + 1)
      // A whole number of at most MaxBits bits, ten thousand digits, is written out as it is.
      val widest  = BigInt(1) << (MessageFormat2.MaxBits - 1)
      val wider   = BigInt(1) << MessageFormat2.MaxBits
      TimeBudget.within("numbers at the bounds on decimals and bits", 2000)(
        List(
          shown(longest),
          shown(longest + "1"),
          shown(scaled),
          shown(past),
          shown(widest, "{$n :string}").length.toString,
          shown(wider, "{$n :string}"),
        )
      ): out =>
        assertTrue(
          longest.length == Operands.MaxNumberText,
          out(0) == "0",
          out(1) == longest + "1",
          out(2) == "0",
          out(3) == past.toString,
          widest.bitLength == MessageFormat2.MaxBits,
          out(4) == "10000",
          out(5) == "{$n :string}",
        )
    },
    // A catalogue's template can name one argument thousands of times, and a list can bring ten
    // thousand numbers from the wire. Scala.js once took three seconds for the first and two for
    // the last. The budgets are an order of magnitude above what each takes alone, because the
    // specs run side by side on a machine that does other things.
    test("a Double that is costly to read is read once, however often a template names it") {
      val bare     = "{$x}" * 16000
      val function = "{$x :number} " * 5000
      val variants = ".match {$x}\n" + "when 1 {a}\n" * 5000 + "when * {none}"
      TimeBudget.within("a subnormal Double in 16000 placeholders, 5000 functions and 5000 variants", 3000)(
        List(bare, function, variants).map(t => MessageFormat2.format(t, Map("x" -> subnormal)))
      ): out =>
        assertTrue(out(0) == "0" * 16000, out(1) == "0 " * 5000, out(2) == "none")
    },
    test("a list of ten thousand Doubles that are costly to read is shown in time") {
      given Catalogues = Catalogues(Seq(Readings))
      val message = Readings.all(values = List.fill(10000)(subnormal))
      TimeBudget.within("a list of 10000 subnormal Doubles", 5000)(message.localize(Language.English)): out =>
        assertTrue(out == List.fill(10000)("0").mkString(", "))
    },
    // Ten thousand different ones are each read, and that is more work than one message may
    // do: it falls back to its template, as a message that writes too much does.
    test("a list of ten thousand different Doubles that are costly to read falls back, in time") {
      given Catalogues = Catalogues(Seq(Readings))
      val different = List.tabulate(10000)(i => subnormal - i * Double.MinPositiveValue)
      val message   = Readings.all(values = different)
      val ordinary  = Readings.all(values = List.tabulate(10000)(i => i + 0.5))
      TimeBudget.within("lists of 10000 different Doubles, subnormal and ordinary", 5000)(
        (message.localize(Language.English), ordinary.localize(Language.English))
      ): (back, shown) =>
        assertTrue(
          different.distinct.size == 10000,
          back == "{$values}",
          shown.startsWith("0.5, 1.5, 2.5, ") && shown.endsWith(", 9,999.5"),
        )
    },
    // Every nested message is an evaluation of its own, and a message that shows one character
    // spends nothing of the limit on text: what one evaluation costs is what a wire message of a
    // thousand of them costs a thousand times. A selector was once parsed again for every
    // selector, a number written again for every placeholder.
    test("a hundred evaluations of a template full of selectors or functions on a costly number") {
      val selectors                             = ".match " + "{$x} " * 9000 + "\nwhen " + "* " * 9000 + "{b}"
      val functions                             = "{$x :number} " * 5000
      val text                                  = "0." + "0" * 1000 + "1"
      def hundred(template: String, value: Any) = (1 to 100).map(_ =>
        MessageFormat2.format(template, Map("x" -> value))
      ).last
      TimeBudget.within("100 x 9000 selectors and 100 x 5000 functions on a subnormal and on long text", 8000)(
        List(
          hundred(selectors, subnormal),
          hundred(functions, subnormal),
          hundred(selectors, text),
          hundred(functions, text),
        )
      ): out =>
        assertTrue(
          selectors.length < 65536,
          out(0) == "b",
          out(1) == "0 " * 5000,
          out(2) == "b",
          out(3) == "0 " * 5000,
        )
    },
    // A number that :integer made is a BigDecimal, which is not kept between uses, and a nested
    // message is an evaluation of its own that may show nothing: a template that declared such a
    // number and used it 2400 times cost a wire message of a thousand nested messages minutes.
    // The work on numbers is counted against the budget the nested messages share.
    test("work on numbers spends the budget of a message, as the text it writes does") {
      val uses     = (1 to 2400).map(i => s".local $$b$i = {$$a :number}\n").mkString
      val template = MessageFormat2.parse(s".local $$a = {$$x :integer}\n${uses}done").message
      val plurals  = MessageFormat2.parse(".match " + "{$x :plural} " * 64 + "\nwhen " + "* " * 64 + "{b}").message
      def evaluations(
        message: MessageFormat2.Message,
        x: Any,
        most: Int,
      ): (Int, Option[String]) =
        val budget  = Budget.fresh()
        val results = Iterator
          .continually(MessageFormat2.formatted(message, Map("x" -> x), "en", budget))
          .take(most)
          .takeWhile(_.isRight)
          .toList
        results.size -> results.lastOption.flatMap(_.toOption)
      TimeBudget.within("a 300-digit number used 2400 times, and 64 plural selectors, until the budget is spent", 5000)(
        (evaluations(template, 1e299, 1000), evaluations(plurals, subnormal, 1000), evaluations(plurals, 5, 1000))
      ):
        (
          large,
          costly,
          small,
        ) =>
          assertTrue(
            // One evaluation fits the budget; a handful of nested ones spend it.
            large._1 >= 1,
            large._1 < 10,
            large._2 == Some("done"),
            // A selector named 64 times is read once: a thousand evaluations fit.
            costly._1 == 1000,
            small == (1000, Some("b")),
          )
    },
    // A date written into a declaration or selected on shows nothing, and a formatter was built
    // for each: 1300 declarations in each of thousands of nested messages took a minute for a
    // 60 KB message. Every step of an evaluation now costs the budget something.
    test("work that writes nothing spends the budget too: dates, names, declarations, selectors, keys") {
      import java.time.{LocalDate, LocalTime}
      val day                                                                  = LocalDate.of(2026, 11, 10)
      def parsed(template: String)                                             = MessageFormat2.parse(template).message
      def locals(expression: String, count: Int)                               =
        parsed((1 to count).map(i => s".local $$b$i = {$expression}\n").mkString + "{(x)}")
      def selectors(expression: String, count: Int)                            =
        parsed(".match " + s"{$expression} " * count + "\nwhen " + "* " * count + "{x}")
      val scenarios: List[(String, MessageFormat2.Message, Any)]               = List(
        ("1300 dates declared", locals("$v :date dateStyle=full", 1300), day),
        ("3700 dates selected", selectors("$v :date", 3700), day),
        ("4500 number keys", parsed(".match {$v}\n" + (1 to 4500).map(i => s"when $i {a}\n").mkString + "when * {x}"), 0),
        ("2400 times shown", parsed("{$v :time timeStyle=short}" * 2400), LocalTime.of(14, 5)),
        ("2950 language names declared", locals("$v :string", 2950), Language("sw")),
        ("4000 language names selected", selectors("$v :string", 4000), Language("sw")),
        ("9000 selectors on a number of ten thousand digits", selectors("$v", 9000), BigInt(10).pow(9999)),
        ("64 plural selectors on a costly Double", selectors("$v :plural", 64), subnormal),
      )
      // How many evaluations one budget pays for, and what the next five thousand cost, which
      // is nothing: an evaluation on a spent budget stops before it does anything.
      def evaluations(message: MessageFormat2.Message, value: Any): (Int, Int) =
        val budget = Budget.fresh()
        val paid   = Iterator
          .continually(MessageFormat2.formatted(message, Map("v" -> value), "en", budget))
          .take(100000)
          .takeWhile(_.isRight)
          .size
        paid -> (1 to 5000).count(_ => MessageFormat2.formatted(message, Map("v" -> value), "en", budget).isLeft)
      TimeBudget.within("eight templates that write one character, until the budget is spent and after", 30000)(
        scenarios.map(
          (
            name,
            message,
            value,
          ) => name -> evaluations(message, value)
        )
      ): counts =>
        // What an evaluation costs follows from the charges alone, so the counts are the same
        // on both platforms, and exact: a charge that goes missing or changes shows here, and
        // whoever changes one changes its count with it.
        val expected = Map(
          "1300 dates declared" -> 2996,
          "3700 dates selected" -> 538,
          "4500 number keys" -> 186,
          "2400 times shown" -> 207,
          "2950 language names declared" -> 1340,
          "4000 language names selected" -> 497,
          "9000 selectors on a number of ten thousand digits" -> 222,
          "64 plural selectors on a costly Double" -> 2574,
        )
        val outside  = counts.filter((name, result) => result != (expected(name), 5000))
        assertTrue(outside.isEmpty)
    },
    test("a list of whole Doubles costs what a list of Ints costs, on both platforms") {
      given Catalogues = Catalogues(Seq(Readings))
      def left(values: List[Double]): Boolean =
        // Four lists of ten thousand in one budget: all four are shown.
        val budget = Budget.fresh()
        (1 to 4).forall(_ =>
          summon[Catalogues].renderWithin(Readings.all(values = values), Language.English, budget).startsWith("0, 1, 2")
        )
      assertTrue(left(List.tabulate(10000)(_.toDouble)))
    },
    test("the shortest digits of a Float are those that rounding its exact value finds, for 20000 values") {
      def reference(f: Float): String =
        val exact = new java.math.BigDecimal(math.abs(f.toDouble))
        val found = (1 to 9).iterator
          .map(p => exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN)))
          .find(c => c.toString.toDouble.toFloat == math.abs(f))
          .getOrElse(exact)
          .stripTrailingZeros
        (if f < 0 then found.negate else found).toPlainString
      val random                      = new scala.util.Random(20261110L)
      val sample                      = Iterator
        .continually(java.lang.Float.intBitsToFloat(random.nextInt()))
        // A whole Float is read as the Double it is, which the Double test covers.
        .filter(f => !f.isNaN && !f.isInfinite && !f.isWhole)
        .take(20000)
        .toList
      val differs                     = sample.filter(f => Decimal.plain(f) != Some(reference(f)))
      assertTrue(differs.isEmpty)
    },
    // Scala.js makes another number altogether of a negative Double of 2^63 and beyond when it
    // makes a BigDecimal of it: -1e19 was shown as 37,768,931,862,957,162,000,000 there.
    test("a negative number is its positive with a minus, however large") {
      def shown(value: Any, template: String = "{$x :string}") =
        MessageFormat2.format(template, Map("x" -> value))
      val large = List(1e19, 1e40, 9.223372036854776e18, Double.MaxValue, 1.5e300)
      assertTrue(
        large.forall(d => shown(-d) == "-" + shown(d)),
        large.forall(d => shown(-d).toDouble == -d),
        shown(-1e19) == "-10000000000000000000",
        shown(-1e19, "{$x :number}") == "-10,000,000,000,000,000,000",
        shown(-1e19f) == "-" + shown(1e19f),
        shown(-1e19f).toFloat == -1e19f,
        shown(-1e19, ".match {$x :plural}\nwhen one {one}\nwhen * {other}") == "other",
        shown(-1e19, ".match {$x}\nwhen -10000000000000000000 {found}\nwhen * {other}") == "found",
      )
    },
    // The digits are found from seventeen of them, not from the exact value each time. They
    // must be the digits the slow way finds: the nearest of the shortest that read back.
    test("the shortest digits are those that rounding the exact value finds, for 20000 values") {
      def reference(d: Double): String =
        val exact = new java.math.BigDecimal(math.abs(d))
        val found = (1 to 17).iterator
          .map(p => exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN)))
          .find(c => c.toString.toDouble == math.abs(d))
          .getOrElse(exact)
          .stripTrailingZeros
        (if d < 0 then found.negate else found).toPlainString
      val random                       = new scala.util.Random(20261110L)
      val sample                       = Iterator
        .continually(java.lang.Double.longBitsToDouble(random.nextLong()))
        .filter(d => !d.isNaN && !d.isInfinite)
        .take(20000)
        .toList
      val named   = List(7.413350156306077e49, 7.844215278549737e-14, 0.1, 1.0e23, 5e-324, 9.5e-322, subnormal)
      val differs = (sample ++ named).filter(d => Decimal.plain(d) != Some(reference(d)))
      assertTrue(differs.isEmpty)
    },
    test("a Float and the Double of the same value keep their own digits, whichever is read first") {
      val args  = Map[String, Any]("f" -> 1.1f, "d" -> 1.1f.toDouble)
      val first = MessageFormat2.format("{$f :string}|{$d :string}", args).split('|').toList
      val last  = MessageFormat2.format("{$d :string}|{$f :string}", args).split('|').toList
      assertTrue(first == last.reverse, first.head == "1.1")
    },
    test("a BigDecimal whose digits are a very long whole number is not written out") {
      val wide = BigDecimal(BigInt(1) << (MessageFormat2.MaxBits + 1), -400)
      assertTrue(
        MessageFormat2.format("{$x :number} {$x}", Map("x" -> wide)) == "{$x :number} {$x}",
        MessageFormat2.format("{$x}", Map("x" -> BigDecimal("1E+1000000000"))) == "1E+1000000000",
      )
    },
    test("the shortest digits of a Double and a Float, also of the smallest and the largest") {
      def digits(value: Any) = Decimal.plain(value)
      assertTrue(
        digits(0.1) == Some("0.1"),
        digits(0.1f) == Some("0.1"),
        digits(1.0) == Some("1"),
        digits(0.30000000000000004) == Some("0.30000000000000004"),
        digits(1.0e21) == Some("1000000000000000000000"),
        digits(123456789.125) == Some("123456789.125"),
        // 5E-324: one digit reads back as the smallest Double, after 323 zeros.
        digits(Double.MinPositiveValue).exists(d => d.endsWith("5") && d.length == 326),
        digits(subnormal).exists(_.toDouble == subnormal),
        digits(Double.MaxValue).exists(d => d.startsWith("17976931348623157") && d.length == 309),
        digits(Float.MaxValue).exists(_.toFloat == Float.MaxValue),
        digits(9007199254740993.0) == Some("9007199254740992"),
      )
    },
    test("values and text read as numbers the same on both platforms") {
      val shown = List[(Any, String)](
        12 -> "12",
        -3L -> "-3",
        1.5 -> "1.5",
        0.1f -> "0.1",
        BigDecimal("12345.678") -> "12,345.678",
        "12" -> "12",
        "-0" -> "0",
        "-0.0" -> "0",
        "1.5" -> "1.5",
        "1e3" -> "1,000",
        "+3" -> "3",
        ".5" -> "0.5",
        "5." -> "5",
        "NaN" -> "NaN",
        "Infinity" -> "Infinity",
        "-Infinity" -> "-Infinity",
        Double.NaN -> "NaN",
        "abc" -> "abc",
        "" -> "",
        "1x" -> "1x",
      )
      val wrong = shown.filter((value, text) => MessageFormat2.format("{$n :number}", Map("n" -> value)) != text)
      assertTrue(wrong.isEmpty)
    },
    test("a plural selector reads its argument once, however many variants it is matched against") {
      // A value that counts how often it is read as text, which is how a number is read.
      final class Counted(n: Int):
        var reads                     = 0
        override def toString: String =
          reads += 1
          n.toString
      val value    = Counted(7)
      val template = ".match {$n :plural}\n" + (1 to 50).map(i => s"when =$i {v$i}").mkString("\n") + "\nwhen * {none}"
      val out      = MessageFormat2.format(template, Map("n" -> value))
      assertTrue(out == "v7", value.reads == 1)
    },
    test("a null argument is no number, and a plural selector on it takes the * variant") {
      val template = ".match {$n :plural}\nwhen one {one}\nwhen * {other}"
      assertTrue(
        // As in 6.0.0: a template that is given no value for a number comes back as written.
        MessageFormat2.format("{$n :number} {$n :integer}", Map("n" -> null)) == "{$n :number} {$n :integer}",
        MessageFormat2.format("{$n}", Map("n" -> null)) == "{$n}",
        MessageFormat2.format(".match {$n}\nwhen null {null}\nwhen * {other}", Map("n" -> null)) ==
        ".match {$n}\nwhen null {null}\nwhen * {other}",
        MessageFormat2.format(template, Map("n" -> null)) == "other",
      )
    },
    test("a selector matches its count as before") {
      val template = ".match {$n :plural}\nwhen =0 {zero}\nwhen one {one}\nwhen * {many}"
      assertTrue(
        MessageFormat2.format(template, Map("n" -> 0)) == "zero",
        MessageFormat2.format(template, Map("n" -> 1)) == "one",
        MessageFormat2.format(template, Map("n" -> "1")) == "one",
        MessageFormat2.format(template, Map("n" -> 7)) == "many",
        MessageFormat2.format(template, Map("n" -> "seven")) == "many",
      )
    },
  )

end NumberArgumentSpec
