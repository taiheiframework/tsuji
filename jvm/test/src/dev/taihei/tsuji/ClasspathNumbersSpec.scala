package dev.taihei.tsuji

import zio.test.*

// A family whose German catalogue file holds a number with an exponent beyond the reader's bound
// (META-INF/taihei-tsuji/numbers/de.json). The classpath exists on the JVM only.
object Numbers extends MessageFamily("numbers"):
  val one = message("One")

object ClasspathNumbersSpec extends ZIOSpecDefault:

  private val German = Language("de")

  def spec = suite("a catalogue file with an out-of-bounds number")(
    test("ClasspathCatalogues.read gives a problem, and does not throw") {
      val read =
        try ClasspathCatalogues.read(Numbers, German)
        catch case _: Throwable => Some(Right(Catalogue(Language("xx"), Map.empty)))
      assertTrue(read.exists(_.left.exists(_.message.contains("exponent of more than 9 digits"))))
    },
    test("loading it while showing a message falls back with a problem, and does not throw") {
      given c: Catalogues = Catalogues()
      val shown =
        try Numbers.one.localize(German)
        catch case t: Throwable => s"threw $t"
      assertTrue(
        shown == "One",
        c.problems.exists(p => p.language.contains(German) && p.message.contains("exponent of more than 9 digits")),
      )
    },
  )

end ClasspathNumbersSpec
