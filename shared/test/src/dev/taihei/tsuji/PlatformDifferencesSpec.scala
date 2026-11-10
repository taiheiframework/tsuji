package dev.taihei.tsuji

import java.time.{LocalDateTime, LocalTime}

import zio.test.*

/**
 * Where the JVM and Scala.js write different text for the same input, and the README says so.
 * Each difference is pinned per platform in PlatformExpectations, so that a change on either side
 * shows here instead of in an application.
 */
object PlatformDifferencesSpec extends ZIOSpecDefault:

  private def shown(template: String, value: Any): String =
    MessageFormat2.format(template, Map("x" -> value), "en")

  // The JDKs and Intl differ in the space before AM and PM (a plain one or U+202F), which is not
  // the difference these tests are about.
  private def plainSpaces(s: String): String = s.replace(' ', ' ')

  def spec = suite("What differs between the platforms")(
    // Up to 6.0.0 these differed: the JVM wrote the Double 3 as 3.0 and JavaScript as 3, a
    // Float 1.1 came out as 1.100000023841858 there, and `when 3` matched 3.0 in JavaScript only.
    test("a Double or a Float is written and matched the same on both platforms") {
      val template = ".match {$x}\nwhen 3 {three}\nwhen * {other}"
      assertTrue(
        shown("{$x}", 3.0) == "3",
        shown("{$x :string}", 3.0) == "3",
        shown("{$x}", 1.1f) == "1.1",
        shown("{$x :string}", 1.1f) == "1.1",
        shown("{$x :number}", 3.0) == "3",
        shown("{$x :integer}", 3.0) == "3",
        shown("{$x :number}", 1.5) == "1.5",
        shown(template, 3.0).trim == "three",
        shown(template, 3).trim == "three",
        shown(template, 3L).trim == "three",
        shown(template, BigDecimal("3.00")).trim == "three",
        shown(template, 3.5).trim == "other",
        // Text matches as text: "3.0" is not the key 3.
        shown(template, "3").trim == "three",
        shown(template, "3.0").trim == "other",
      )
    },
    // A LocalTime and a LocalDateTime have no zone, which the long and full styles print: the JVM
    // falls back to medium, Intl names UTC.
    test("a long or full timeStyle on a value without a zone") {
      val time = LocalTime.of(14, 30)
      val both = LocalDateTime.of(2024, 1, 15, 14, 30)
      assertTrue(
        plainSpaces(shown("{$x :time timeStyle=long}", time)) == PlatformExpectations.longTime,
        plainSpaces(shown("{$x :time timeStyle=full}", time)) == PlatformExpectations.fullTime,
        plainSpaces(shown("{$x :datetime timeStyle=long}", both)) ==
        s"Jan 15, 2024, ${PlatformExpectations.longTime}",
      )
    },
    // The JDK names a language with a region as the language and the region, Intl has names of
    // its own for some.
    test("the name of a language with a region") {
      assertTrue(
        Language.nameIn(Language("nl-BE"), "en") == PlatformExpectations.dutchOfBelgium,
        Language.nameIn(Language("en-GB"), "en") == PlatformExpectations.englishOfBritain,
        Language.nameIn(Language("de"), "en") == "German",
      )
    },
  )

end PlatformDifferencesSpec
