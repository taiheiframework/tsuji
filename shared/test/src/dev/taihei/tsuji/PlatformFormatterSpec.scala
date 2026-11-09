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

  private def number(tag: String)  = PlatformNumberFormatter.formatNumber(1234.5, None, tag)
  private def percent(tag: String) = PlatformNumberFormatter.formatNumber(0.5, Some("percent"), tag)
  private def medium(tag: String)  = PlatformDateTimeFormatter.formatDateTime(date, Map.empty, tag)

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
        number("en") == "1,234.50",
        percent("en") == "50%",
        medium("en") == "Oct 20, 2026",
        long("en") == "October 20, 2026",
        PlatformNumberFormatter.formatNumber(1234.5, Some("currency"), "en") == "$1,234.50",
      )
    },
    test("Dutch is unchanged") {
      assertTrue(
        number("nl") == "1.234,50",
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
      val shown    = MessageFormat2.format(template, args, Language.Dutch.tag)
      assertTrue(
        Language.Dutch.tag == "nl",
        shown.startsWith("1.234,50 3 20 oktober 2026 14:05 "),
        mediumDutch.contains(shown.stripPrefix("1.234,50 3 20 oktober 2026 14:05 ")),
      )
    },
    test("French is unchanged") {
      assertTrue(
        number("fr") == "1 234,50",
        percent("fr") == "50 %",
        medium("fr") == "20 oct. 2026",
        long("fr") == "20 octobre 2026",
        time("fr") == "14:05",
      )
    },
    test("German formats as German") {
      assertTrue(
        number("de") == "1.234,50",
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
      assertTrue(number("not a tag") == "1,234.50", medium("") == "Oct 20, 2026")
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
        MessageFormat2.format("{$d :date dateStyle=long}", Map("d" -> year50), "de") ==
        "1. Januar 50",
      )
    },
    test("both platforms write the date and time they are given") {
      // 02:30 on 2026-03-29 does not exist in Central Europe: the clocks jump from 02:00 to 03:00.
      val gap = LocalDateTime.of(2026, 3, 29, 2, 30)
      assertTrue(
        PlatformDateTimeFormatter.formatDateTime(gap, Map.empty, "de") == "29.03.2026, 02:30:00",
        PlatformDateTimeFormatter.formatDateTime(gap.toLocalTime, Map("timeStyle" -> "short"), "de") ==
        "02:30",
      )
    },
    test("a region reaches the formatter") {
      val msg = Message.withNamedArgs(Language.Dutch -> "{$x :number}")("x" -> 1234.5)
      val be  = msg.translate(Language("nl-BE"))
      assertTrue(number("nl-BE") == "1.234,50", be == "1.234,50")
    },
  )

end PlatformFormatterSpec
