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
        MessageFormat2.format("{$n :number} {$n :integer}", Map("n" -> null)) == "null null",
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
