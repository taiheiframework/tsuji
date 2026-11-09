package dev.taihei.tsuji

import scala.util.Try

import zio.test.*

// An argument can be text from the wire, and `:number` and `:plural` read it as a number. That
// read must take time linear in the text on both platforms, and happen once per selector.
object NumberArgumentSpec extends ZIOSpecDefault:

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
    test("text longer than the JSON bound on a number is not read as one") {
      val long = "1" * (JsonTreeReader.MaxNumberLength + 1)
      assertTrue(
        MessageFormat2.numberOf(long).isEmpty,
        MessageFormat2.numberOf("1" * JsonTreeReader.MaxNumberLength).isDefined,
      )
    },
    test("numbers read as before, as values and as text") {
      val inputs  = List[Any](
        12,
        -3L,
        1.5,
        0.1f,
        BigDecimal("12345.678"),
        "12",
        "-0",
        "1.5",
        "1e3",
        "+3",
        ".5",
        "5.",
        "NaN",
        "Infinity",
        "-Infinity",
        "abc",
        "",
        "1x",
      )
      val changed = inputs.filter(v =>
        MessageFormat2.numberOf(v).map(_.toString) != Try(v.toString.toDouble).toOption.map(_.toString)
      )
      assertTrue(changed.isEmpty)
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
      assertTrue(MessageFormat2.numberOf(null).isEmpty, MessageFormat2.format(template, Map("n" -> null)) == "other")
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
