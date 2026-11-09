package dev.taihei.tsuji

import java.io.{ByteArrayInputStream, InputStream}

import zio.test.*

import dev.taihei.tsuji.Language.*

// The classpath exists on the JVM only.
object ClasspathCatalogueSpec extends ZIOSpecDefault:

  private val German  = Language("de")
  private val Spanish = Language("es")

  def spec = suite("catalogues on the classpath")(
    test("a family's language is read the first time it is needed") {
      given c: Catalogues = Catalogues()
      val before    = c.languages
      val cancelled = Orders.cancelled.localize(German)
      assertTrue(
        before.isEmpty,
        cancelled == "Ihre Bestellung wurde storniert",
        Orders.greeting(name = "Ann").localize(Language("de-AT")) == "Hallo Ann",
        c.languages.contains(German),
        c.problems.exists(p => p.key.map(_.value).contains("shape.circle") && p.message.contains("not a key of order")),
      )
    },
    test("a file in the wrong language, with a bad template, or not in UTF-8 falls back with a problem") {
      given c: Catalogues = Catalogues()
      assertTrue(
        Orders.cancelled.localize(French) == "Your order was cancelled",
        Orders.cancelled.localize(Spanish) == "Your order was cancelled",
        Shapes.circle.localize(German) == "Cirkel",
        c.problems.exists(_.message.contains("says it is de")),
        c.problems.exists(p => p.language.contains(Spanish) && p.message.contains("not closed")),
        c.problems.exists(_.message.contains("not UTF-8")),
      )
    },
    test("ClasspathCatalogues reads and verifies the files") {
      val problems = ClasspathCatalogues.verify(Seq(Orders), Seq(German, French, Spanish), required = Set(German))
      assertTrue(
        ClasspathCatalogues.read(Orders, German).exists(_.exists(_.messages.size == 2)),
        ClasspathCatalogues.read(Orders, Language("ja")).isEmpty,
        problems.exists(_.message.contains("says it is de")),
        problems.exists(p => p.language.contains(Spanish) && p.message.contains("not closed")),
        problems.exists(p => p.key.contains(Orders.shipped.key) && p.message.contains("missing in a required language")),
        problems.exists(_.message.contains("not a key of order")),
      )
    },
    test("a flood of languages without files does not keep a supported one from loading") {
      given c: Catalogues = Catalogues()
      (1 to 5000).foreach(i => Orders.cancelled.localize(Language(s"en-x-n$i")))
      assertTrue(Orders.cancelled.localize(German) == "Ihre Bestellung wurde storniert")
    },
    test("a file larger than the limit is refused after reading the limit, not all of it") {
      var served  = 0L
      val endless = new InputStream:
        def read(): Int =
          served += 1; 'a'
        override def read(
          b: Array[Byte],
          off: Int,
          len: Int,
        ): Int          =
          java.util.Arrays.fill(b, off, off + len, 'a'.toByte)
          served += len
          len
      val result  = PlatformCatalogueResources.readBounded(endless, "endless.json")
      assertTrue(result.left.exists(_.contains("larger than")), served == Catalogue.MaxLength.toLong * 4 + 1)
    },
    test("a file of exactly the limit is read, and one byte more is refused") {
      val limit = Catalogue.MaxLength * 4
      val at    = PlatformCatalogueResources.readBounded(ByteArrayInputStream(Array.fill(limit)('a'.toByte)), "at.json")
      val past  =
        PlatformCatalogueResources.readBounded(ByteArrayInputStream(Array.fill(limit + 1)('a'.toByte)), "past.json")
      assertTrue(
        at.map(_.length) == Right(limit),
        past == Left(s"past.json is larger than $limit bytes"),
      )
    },
    test("a file that is not UTF-8 is refused, and one that is reads as its text") {
      val malformed = PlatformCatalogueResources.readBounded(
        ByteArrayInputStream(Array(0x7b, 0xc3, 0x28, 0x7d).map(_.toByte)),
        "bad.json",
      )
      val utf8      =
        PlatformCatalogueResources.readBounded(ByteArrayInputStream("{\"ü\": \"€\"}".getBytes("UTF-8")), "good.json")
      val empty     = PlatformCatalogueResources.readBounded(ByteArrayInputStream(Array.emptyByteArray), "empty.json")
      assertTrue(
        malformed == Left("bad.json is not UTF-8"),
        utf8 == Right("{\"ü\": \"€\"}"),
        empty == Right(""),
      )
    },
  )

end ClasspathCatalogueSpec
