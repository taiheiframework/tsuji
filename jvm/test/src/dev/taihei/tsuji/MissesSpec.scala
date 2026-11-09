package dev.taihei.tsuji

import zio.test.*

// A registry looks for catalogue files on the classpath, which exists on the JVM only: in Scala.js
// it never looks, so it never misses.
object MissesSpec extends ZIOSpecDefault:

  // Whether the first, second and last language's missing file is still remembered after a
  // message of Orders was shown in each of `n` languages that have no file.
  private def remembered(n: Int): (Boolean, Boolean, Boolean) =
    given c: Catalogues = Catalogues()
    val languages                = PlainLanguages(n)
    languages.foreach(Orders.cancelled.localize(_))
    def kept(language: Language) = c.lookedFor("order", language)
    (kept(languages.head), kept(languages(1)), kept(languages.last))

  def spec = suite("Misses")(
    test("a registry remembers the latest 4096 families and languages without a file") {
      assertTrue(
        PlainLanguages(4097).size == 4097,
        remembered(4096) == (true, true, true),
        remembered(4097) == (false, true, true),
      )
    }
  )

end MissesSpec
