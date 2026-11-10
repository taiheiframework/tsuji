package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import zio.test.*

import dev.taihei.tsuji.legacy.Message

/**
 * The formatters on both platforms. The en/nl/fr expectations are the output from before
 * 2.0.0, taken on the JVM and in Node; German is what any language now gets instead of the
 * JVM's default locale or JavaScript's en-US.
 */
object PlatformFormatterSpec extends ZIOSpecDefault:

  private val date = LocalDate.of(2026, 10, 20)

  private def number(tag: String) = MessageFormat2.formatIn("{$n :number}", Map("n" -> 1234.5), tag)

  private def percent(tag: String) =
    MessageFormat2.formatIn("{$n :number style=percent}", Map("n" -> 0.5), tag)

  private def medium(tag: String) = PlatformDateTimeFormatter.formatDateTime(date, Map.empty, tag)

  private def long(tag: String) =
    PlatformDateTimeFormatter.formatDateTime(date, Map("dateStyle" -> "long"), tag)

  // The abbreviated Dutch month in the medium style comes from the platform's locale data, which
  // differs between Java 17 and later JDKs (PlatformExpectations says what each platform writes).
  private val mediumDutch = PlatformExpectations.mediumDutchDates

  private def time(tag: String) =
    PlatformDateTimeFormatter.formatDateTime(LocalTime.of(14, 5), Map("timeStyle" -> "short"), tag)

  def spec = suite("PlatformFormatterSpec")(
    test("English is unchanged") {
      assertTrue(
        number("en") == "1,234.5",
        percent("en") == "50%",
        medium("en") == "Oct 20, 2026",
        long("en") == "October 20, 2026",
        MessageFormat2.format("{$n :number style=currency currency=USD}", Map("n" -> 1234.5), Language.English) ==
        "$1,234.50",
      )
    },
    test("Dutch is unchanged") {
      assertTrue(
        number("nl") == "1.234,5",
        percent("nl") == "50%",
        mediumDutch.contains(medium("nl")),
        long("nl") == "20 oktober 2026",
        time("nl") == "14:05",
      )
    },
    // Language.Dutch is the bare tag nl. On the JVM its locale once came from Locale.of, which
    // Java 17 lacks: there the first Dutch :number or :date threw NoSuchMethodError. CI runs this
    // on Java 17 as well as on the newest JDK.
    test("a Dutch message formats :number, :integer, :date, :time and :datetime") {
      val template =
        "{$n :number} {$i :integer} {$d :date dateStyle=long} {$t :time timeStyle=short} {$d :datetime}"
      val args     = Map("n" -> 1234.5, "i" -> 2.5, "d" -> date, "t" -> LocalTime.of(14, 5))
      val shown    = MessageFormat2.formatIn(template, args, Language.Dutch.tag)
      assertTrue(
        Language.Dutch.tag == "nl",
        shown.startsWith("1.234,5 3 20 oktober 2026 14:05 "),
        mediumDutch.contains(shown.stripPrefix("1.234,5 3 20 oktober 2026 14:05 ")),
      )
    },
    test("French is unchanged") {
      assertTrue(
        number("fr") == "1 234,5",
        percent("fr") == "50 %",
        medium("fr") == "20 oct. 2026",
        long("fr") == "20 octobre 2026",
        time("fr") == "14:05",
      )
    },
    test("German formats as German") {
      assertTrue(
        number("de") == "1.234,5",
        percent("de") == "50 %",
        medium("de") == "20.10.2026",
        long("de") == "20. Oktober 2026",
        time("de") == "14:05",
      )
    },
    test("a message in German formats its arguments in German") {
      val msg = Message.withNamedArgs(Language.English -> "{$d :datetime dateStyle=long}")(
        "d" -> date
      )
      val de  = msg.translate(Language("de"))
      val en  = msg.translate(Language.English)
      assertTrue(de == "20. Oktober 2026", en == "October 20, 2026")
    },
    test("something that is not a tag formats as English") {
      assertTrue(number("not a tag") == "1,234.5", medium("") == "Oct 20, 2026")
    },
    test("a valid tag the platform cannot format does not throw") {
      assertTrue(number("zh-cmn-Hans").nonEmpty, medium("zh-cmn-Hans").nonEmpty)
    },
    // Scala.js once built the Date in the runtime's zone and read years 0 to 99 as 19xx. The
    // expectations are the JVM's output, which both platforms must write.
    test("both platforms write year 50 as year 50") {
      val year50 = LocalDate.of(50, 1, 1)
      assertTrue(
        PlatformDateTimeFormatter.formatDateTime(year50, Map.empty, "de") == "01.01.50",
        PlatformDateTimeFormatter.formatDateTime(year50, Map("dateStyle" -> "long"), "en") ==
        "January 1, 50",
        MessageFormat2.format("{$d :date dateStyle=long}", Map("d" -> year50), Language("de")) ==
        "1. Januar 50",
      )
    },
    test("both platforms write the date and time they are given") {
      // 02:30 on 2026-03-29 does not exist in Central Europe: the clocks jump from 02:00 to 03:00.
      // Under UTC that proves little; RuntimeZoneSpec runs the same value in Europe/Brussels.
      val gap = LocalDateTime.of(2026, 3, 29, 2, 30)
      assertTrue(
        PlatformDateTimeFormatter.formatDateTime(gap, Map.empty, "de") == "29.03.2026, 02:30:00",
        PlatformDateTimeFormatter.formatDateTime(gap.toLocalTime, Map("timeStyle" -> "short"), "de") ==
        "02:30",
      )
    },
    // Up to 6.0.0 a whole number in Int range was written without grouping (1234567), a larger
    // one with two decimals (3,000,000,000.00), and a Long past 2^53 lost its last digits on the
    // way through a Double.
    test("a whole number is grouped as the language groups, whatever its size or type") {
      def shown(
        n: Any,
        template: String = "{$n :number}",
        tag: String = "en",
      ) =
        MessageFormat2.formatIn(template, Map("n" -> n), tag)
      assertTrue(
        shown(1234567) == "1,234,567",
        shown(1234567, tag = "nl") == "1.234.567",
        shown(3000000000L) == "3,000,000,000",
        shown(3e9) == "3,000,000,000",
        shown(-3e9) == "-3,000,000,000",
        shown(9007199254740993L) == "9,007,199,254,740,993",
        shown(9007199254740993L, "{$n :integer}") == "9,007,199,254,740,993",
        shown(BigInt("123456789012345678901")) == "123,456,789,012,345,678,901",
        shown(BigDecimal("12345678901234567890.25")) == "12,345,678,901,234,567,890.25",
        // A half is rounded away from zero on both platforms.
        shown(2.5, "{$n :integer}") == "3",
        shown(-2.5, "{$n :integer}") == "-3",
        shown(1234.5, "{$n :integer}") == "1,235",
        shown(2L, "{$n :number style=percent}") == "200%",
      )
    },
    test("a number has up to three decimals, or as many as the template asks") {
      def shown(
        n: Any,
        options: String = "",
        tag: String = "en",
      ) =
        MessageFormat2.formatIn(s"{$$n :number $options}", Map("n" -> n), tag)
      assertTrue(
        shown(1.5) == "1.5",
        shown(1.0) == "1",
        shown(0.1f) == "0.1",
        shown(12345.6789) == "12,345.679",
        shown(1.0005) == "1.001",
        shown(1.5, "minimumFractionDigits=2") == "1.50",
        shown(1.23456, "maximumFractionDigits=1") == "1.2",
        shown(1.5, "minimumFractionDigits=2 maximumFractionDigits=4", "nl") == "1,50",
        shown(1.5, "minimumFractionDigits=5") == "1.50000",
        // The wrong way round: the larger of the two holds.
        shown(1.5, "minimumFractionDigits=3 maximumFractionDigits=1") == "1.500",
        shown(0.256, "style=percent maximumFractionDigits=1") == "25.6%",
        shown(2026, "useGrouping=never") == "2026",
        shown(2026) == "2,026",
      )
    },
    test("a number or a date without a function is written as :number and :datetime write it") {
      def shown(value: Any, tag: String = "en") = MessageFormat2.formatIn("{$x}", Map("x" -> value), tag)
      assertTrue(
        shown(1234567) == "1,234,567",
        shown(1234.5, "nl") == "1.234,5",
        shown(1.0) == "1",
        shown(date) == "Oct 20, 2026",
        shown(date, "de") == "20.10.2026",
        shown(LocalTime.of(14, 5), "nl") == "14:05:00",
        // Text is text, also when it reads as a number.
        shown("1234567") == "1234567",
        // :string is the way to show a number as it is: an order number, a year.
        MessageFormat2.format("{$x :string}", Map("x" -> 1234567), Language.English) == "1234567",
        MessageFormat2.format("{$x :string}", Map("x" -> 1.0), Language.English) == "1",
        MessageFormat2.format("{$x :string}", Map("x" -> date), Language.English) == "2026-10-20",
      )
    },
    test("an amount in a named currency is written as the reader's language writes amounts") {
      def amount(
        tag: String,
        value: Any = 1234.5,
        code: String = "EUR",
      ) =
        MessageFormat2.formatIn(s"{$$a :number style=currency currency=$code}", Map("a" -> value), tag)
      assertTrue(
        amount("en") == "€1,234.50",
        amount("nl") == "€\u00a01.234,50",
        amount("fr") == "1\u202f234,50\u00a0€",
        amount("de") == "1.234,50\u00a0€",
        amount("en", code = "eur") == "€1,234.50",
        // The yen has no decimals.
        amount("en", 1234, "JPY") == "¥1,234",
        amount("en", 1234.5, "ZZZ").contains("ZZZ"),
        amount("en", 1234.5, "ZZZ").contains("1,234.50"),
      )
    },
    // Up to 6.0.0 an amount was US dollars whatever the template said, on the JVM in the US
    // format whatever the reader's language.
    test("without a currency there is no amount, only a number") {
      def amount(template: String) = MessageFormat2.format(template, Map("a" -> 1234.5), Language.English)
      assertTrue(
        amount("{$a :number style=currency}") == "1,234.5",
        amount("{$a :number style=currency currency=euro}") == "1,234.5",
        amount("{$a :number currency=EUR}") == "1,234.5",
      )
    },
    // The tag of the String form may be a request's, of any length. The JVM reads the language
    // at its start, Scala.js takes a tag that is no language tag for English: either way the
    // text is one of the two, and the megabyte is not kept.
    test("a tag longer than a language tag may be formats as its language or as English") {
      val long   = "de-x-" + "abcdefgh-" * 120000 + "z"
      val plural = ".match {$n :plural}\nwhen one {one}\nwhen few {few}\nwhen * {other}"
      assertTrue(
        Set("1,234.5", "1.234,5")(MessageFormat2.formatIn("{$n :number}", Map("n" -> 1234.5), long)),
        Set("Oct 20, 2026", "20.10.2026")(MessageFormat2.formatIn("{$d :date}", Map("d" -> date), long)),
        MessageFormat2.formatIn(plural, Map("n" -> 1), long).trim == "one",
        MessageFormat2.formatIn(plural, Map("n" -> 3), "pl-x-" + "abcdefgh-" * 120000 + "z").trim == "few",
      )
    },
    test("a whole number of more than a million digits is not written out") {
      val huge     = BigInt(2).pow(3500000)
      val template = "{$n :number} {$n :integer}"
      assertTrue(
        MessageFormat2.format(template, Map("n" -> huge), Language.English) == template,
        MessageFormat2.format("{$n :number style=percent}", Map("n" -> huge), Language.English) ==
        "{$n :number style=percent}",
        MessageFormat2.format("{$n :number useGrouping=never}", Map("n" -> BigInt(10).pow(30)), Language.English) ==
        "1" + "0" * 30,
      )
    },
    test("a region reaches the formatter") {
      val msg = Message.withNamedArgs(Language.Dutch -> "{$x :number}")("x" -> 1234.5)
      val be  = msg.translate(Language("nl-BE"))
      assertTrue(number("nl-BE") == "1.234,5", be == "1.234,5")
    },
  )

end PlatformFormatterSpec
