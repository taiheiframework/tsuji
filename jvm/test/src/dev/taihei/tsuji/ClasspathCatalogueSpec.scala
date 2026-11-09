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
    test("a stream that gives a few bytes at a time is read whole, and refused past the limit") {
      // A jar entry gives fewer bytes than asked; this one gives at most three per call.
      def trickle(bytes: Array[Byte]) = new java.io.FilterInputStream(ByteArrayInputStream(bytes)):
        override def read(
          b: Array[Byte],
          off: Int,
          len: Int,
        ): Int = super.read(b, off, math.min(len, 3))
      val text                        = "{\"ü\": \"€ 𝄞\"}" * 100
      val small = PlatformCatalogueResources.readBounded(trickle(text.getBytes("UTF-8")), "small.json")
      val limit = Catalogue.MaxLength * 4
      val large = PlatformCatalogueResources.readBounded(trickle(Array.fill(limit + 1)('a'.toByte)), "large.json")
      assertTrue(small == Right(text), large == Left(s"large.json is larger than $limit bytes"))
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
    test("a file that starts with a byte order mark is refused, and the same file without one is read") {
      val text     = "{\"format\":1,\"language\":\"de\",\"messages\":{\"order.cancelled\":\"Storniert\"}}"
      val bom      = Array(0xef, 0xbb, 0xbf).map(_.toByte)
      val withMark =
        PlatformCatalogueResources.readBounded(ByteArrayInputStream(bom ++ text.getBytes("UTF-8")), "de.json")
      val without  = PlatformCatalogueResources.readBounded(ByteArrayInputStream(text.getBytes("UTF-8")), "de.json")
      // Not at the start, the mark is a character like any other: here inside a template.
      val inside   = text.replace("Storniert", "Stor\uFEFFniert")
      val midway   = PlatformCatalogueResources.readBounded(ByteArrayInputStream(inside.getBytes("UTF-8")), "de.json")
      // Before this change the mark reached the parser, which refused the file as not JSON.
      assertTrue(
        withMark == Left("de.json starts with a byte order mark; save it as UTF-8 without one"),
        without == Right(text),
        without.flatMap(Catalogue.parse(_).left.map(_.message)).map(_.messages.size) == Right(1),
        midway == Right(inside),
      )
    },
    test("a classpath file that starts with a byte order mark falls back with that one problem") {
      given c: Catalogues = Catalogues()
      val Italian = Language("it")
      val shown   = Orders.cancelled.localize(Italian)
      val refusal = "META-INF/taihei-tsuji/order/it.json starts with a byte order mark; save it as UTF-8 without one"
      assertTrue(
        shown == "Your order was cancelled",
        c.problems.filter(_.language.contains(Italian)).map(_.message) == List(refusal),
        ClasspathCatalogues.read(Orders, Italian) == Some(Left(CatalogueProblem(refusal, Some(Italian)))),
      )
    },
  )

end ClasspathCatalogueSpec
