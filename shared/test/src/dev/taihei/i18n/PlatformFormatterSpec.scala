package dev.taihei.i18n

import java.time.{LocalDate, LocalTime}

import zio.test.*

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
        PlatformExpectations.mediumDutchDates.contains(medium("nl")),
        long("nl") == "20 oktober 2026",
        time("nl") == "14:05",
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
      assertTrue(
        msg.translate(Language("de")) == "20. Oktober 2026",
        msg.translate(Language.English) == "October 20, 2026",
      )
    },
    test("something that is not a tag formats as English") {
      assertTrue(number("not a tag") == "1,234.50", medium("") == "Oct 20, 2026")
    },
    test("a valid tag the platform cannot format does not throw") {
      assertTrue(number("zh-cmn-Hans").nonEmpty, medium("zh-cmn-Hans").nonEmpty)
    },
    test("a region reaches the formatter") {
      val msg = Message.withNamedArgs(Language.Dutch -> "{$x :number}")("x" -> 1234.5)
      assertTrue(
        number("nl-BE") == "1.234,50",
        msg.translate(Language("nl-BE")) == "1.234,50",
      )
    },
  )

end PlatformFormatterSpec
